package queue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import net.spy.memcached.internal.OperationFuture;

import com.carrotsearch.hppc.IntObjectOpenHashMap;
import com.carrotsearch.hppc.IntOpenHashSet;
import com.couchbase.client.CouchbaseClient;

class MailStorageQueueThread extends Thread
{
	private final static int RETRY_DELAY = 50;
	
	private final AtomicBoolean running = new AtomicBoolean(true);
	private final BlockingQueue<SerializedMail> incomingQueue;
	private final IntObjectOpenHashMap<ReceiverLookupDocument> lookupMap;
	private final IntOpenHashSet receiverOnQueue;
	private final int threadNo;
	private final MailStorageQueue mailStorageQueue;
	private final CouchbaseClient client;
	private final int maxRetries;
	
	private int maxTriesUsed = 0;
	
	protected MailStorageQueueThread(final MailStorageQueue mailStorageQueue, final int threadNo, final CouchbaseClient client, final int maxRetries)
	{
		super("MailStorageQueue-thread-" + threadNo);
		this.incomingQueue = new LinkedBlockingQueue<SerializedMail>();
		this.lookupMap = new IntObjectOpenHashMap<ReceiverLookupDocument>();
		this.receiverOnQueue = new IntOpenHashSet();
		this.threadNo = threadNo;
		this.mailStorageQueue = mailStorageQueue;
		this.client = client;
		this.maxRetries = maxRetries;
	}

	@Override
	public void run()
	{
		while (running.get() || incomingQueue.size() > 0)
		{
			try
			{
				final SerializedMail mail = incomingQueue.take();
				final int receiverID = mail.getReceiverID();
				if (mail != null)
				{
					if (mail instanceof PoisionedSerializedMail)
					{
						// Do nothing
						// This mail just unblocks the queue so the thread can shutdown properly
					}
					else if (mail instanceof JustPersistLookupDocument)
					{
						// Persist lookup document
						if (receiverOnQueue.contains(receiverID))
						{
							if (!persistLookup(receiverID, createLookupDocumentKey(receiverID), lookupMap.get(receiverID)))
							{
								//System.err.println("#" + threadNo + " Re-adding lookup document to queue for receiver " + receiverID);
								incomingQueue.add(mail);
							}
						}
					}
					else
					{
						// Persist mail
						if (persistMail(mail))
						{
							if (!addMailToReceiverLookupDocument(mail))
							{
								//System.err.println("#" + threadNo + " Adding lookup document to queue for receiver " + receiverID);
								removeMailFromReceiverLookupDocument(mail);
							}
						}
						else
						{
							//System.err.println("#" + threadNo + " Re-Adding mail to queue for receiver " + receiverID + ": mail_" + mail.getKey());
							incomingQueue.add(mail);
						}
					}
				}
			}
			catch (InterruptedException e)
			{
				System.err.println("Could not take mail from queue: " + e.getMessage());
			}
		}
		if (maxTriesUsed > (maxRetries / 2))
		{
			System.out.println("#" + threadNo + " Max tries used: " + maxTriesUsed);
		}
		mailStorageQueue.countdown();
	}
	
	protected void addMail(final SerializedMail mail)
	{
		incomingQueue.add(mail);
	}
	
	protected int size()
	{
		return incomingQueue.size();
	}
	
	protected void shutdown()
	{
		running.set(false);
		incomingQueue.add(new PoisionedSerializedMail());
	}
	
	private boolean addMailToReceiverLookupDocument(final SerializedMail mail)
	{
		final int receiverID = mail.getReceiverID();
		final String lookupDocumentKey = createLookupDocumentKey(receiverID);

		// Get lookup document
		ReceiverLookupDocument mailReceiverLookup = lookupMap.get(receiverID);
		if (mailReceiverLookup == null)
		{
			mailReceiverLookup = getLookup(receiverID, lookupDocumentKey);
			if (mailReceiverLookup == null)
			{
				return false;
			}
			lookupMap.put(receiverID, mailReceiverLookup);
		}
			
		// Modify lookup document
		if (!mailReceiverLookup.add(mail.getCreated(), mail.getKey()))
		{
			System.err.println("#" + threadNo + " Could not add mail with same key for mail " + mail.getKey());
			return false;
		}
		
		// Store lookup document
		return persistLookup(receiverID, lookupDocumentKey, mailReceiverLookup);
	}
	
	private void removeMailFromReceiverLookupDocument(final SerializedMail mail)
	{
		final int receiverID = mail.getReceiverID();
		final String lookupDocumentKey = createLookupDocumentKey(receiverID);

		ReceiverLookupDocument mailReceiverLookup = lookupMap.get(receiverID);
		if (lookupMap != null)
		{
			mailReceiverLookup.remove(lookupDocumentKey);
		}
		
		if (!receiverOnQueue.contains(receiverID))
		{
			receiverOnQueue.add(receiverID);
			incomingQueue.add(new JustPersistLookupDocument(receiverID));
		}
	}
	
	private ReceiverLookupDocument getLookup(final int receiverID, final String lookupDocumentKey)
	{
		Object receiverLookupDocumentObject = null;
		boolean retry = true;
		int tries = 0;
		while (retry && tries++ < maxRetries)
		{
			if (tries > maxTriesUsed)
			{
				maxTriesUsed = tries;
			}
			retry = false;
			try
			{
				receiverLookupDocumentObject = client.get(lookupDocumentKey);
			}
			catch (Exception e)
			{
				retry = true;
				System.err.println("#" + threadNo + " Could not read lookup document for " + receiverID + ": " + e.getMessage());
				threadSleep(tries * RETRY_DELAY);
			}
		}
		if (retry)
		{
			System.err.println("#" + threadNo + " Could not read lookup document for " + receiverID + " (permanently)");
			return null;
		}
		else if (receiverLookupDocumentObject != null)
		{
			return ReceiverLookupDocument.fromJSON((String)receiverLookupDocumentObject);
		}
		else
		{
			return new ReceiverLookupDocument();
		}
	}
	
	private boolean persistLookup(final int receiverID, final String lookupDocumentKey, final ReceiverLookupDocument mailReceiverLookup)
	{
		final String document = mailReceiverLookup.toJSON();
		int tries = 0;
		while (tries++ < maxRetries)
		{
			if (tries > maxTriesUsed)
			{
				maxTriesUsed = tries;
			}
			try
			{
				OperationFuture<Boolean> lookupFuture = client.set(lookupDocumentKey, 0, document);
				if (lookupFuture.get())
				{
					receiverOnQueue.remove(receiverID);
					printStatusMessage("#" + threadNo + " Lookup document for receiver " + receiverID + " successfully saved", mailReceiverLookup);
					return true;
				}
			}
			catch (Exception e)
			{
				mailReceiverLookup.setLastException(e);
				System.err.println("#" + threadNo + " Could not set lookup document for receiver " + receiverID + ": " + e.getMessage());
				threadSleep(tries * RETRY_DELAY);
			}
		}
		mailReceiverLookup.incrementTries();
		threadSleep(RETRY_DELAY);
		return false;
	}
	
	private boolean persistMail(final SerializedMail mail)
	{
		int tries = 0;
		while (tries++ < maxRetries)
		{
			if (tries > maxTriesUsed)
			{
				maxTriesUsed = tries;
			}
			try
			{
				OperationFuture<Boolean> mailFuture = client.set("mail_" + mail.getKey(), 0, mail.getDocument());
				if (mailFuture.get())
				{
					printStatusMessage("#" + threadNo + " Mail mail_" + mail.getKey() + " successfully saved", mail);
					return true;
				}
			}
			catch (Exception e)
			{
				mail.setLastException(e);
				System.err.println("#" + threadNo + " Could not add mail_" + mail.getKey() + " for receiver " + mail.getReceiverID() + ": " + e.getMessage());
				threadSleep(tries * RETRY_DELAY);
			}
		}
		mail.incrementTries();
		threadSleep(RETRY_DELAY);
		return false;
	}
	
	private String createLookupDocumentKey(final int receiverID)
	{
		return "receiver_" + receiverID;
	}
	
	private void printStatusMessage(String statusMessage, final StatusMessageObject statusMessageObject)
	{
		Boolean showMessage = false;
		if (statusMessageObject.getTries() > 0)
		{
			statusMessage += ", Tries: " + statusMessageObject.getTries();
			showMessage = true;
		}
		if (statusMessageObject.getLastException() != null)
		{
			statusMessage += ", Last Exception " + statusMessageObject.getLastException().getMessage();
			showMessage = true;
		}
		if (showMessage)
		{
			System.out.println(statusMessage);
		}
		statusMessageObject.resetStatus();
	}

	private void threadSleep(final long milliseconds)
	{
		try
		{
			Thread.sleep(milliseconds);
		}
		catch (InterruptedException e)
		{
		}
	}
	
	private class PoisionedSerializedMail extends SerializedMail
	{
		public PoisionedSerializedMail()
		{
			super(0, 0, null, null);
		}
	}
	
	private class JustPersistLookupDocument extends SerializedMail
	{
		public JustPersistLookupDocument(final int receiverID)
		{
			super(receiverID, 0, null, null);
		}
	}
}
