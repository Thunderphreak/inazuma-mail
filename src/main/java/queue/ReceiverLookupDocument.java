package queue;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.google.gson.Gson;

public class ReceiverLookupDocument
{
	final ConcurrentMap<String, Long> lookup = new ConcurrentHashMap<String, Long>();
	
	public ReceiverLookupDocument()
	{
	}
	
	private ReceiverLookupDocument(Map<String, Long> lookup)
	{
		this.lookup.putAll(lookup);
	}
	
	public boolean add(final long created, final String mailKey)
	{
		if (lookup.putIfAbsent(mailKey, created) != null)
		{
			return false;
		}
		return true;
	}
	
	public void remove(final String mailKey)
	{
		lookup.remove(mailKey);
	}
	
	public int size()
	{
		return lookup.size();
	}

	public Set<String> keySet()
	{
		return lookup.keySet();
	}

	public String toJSON()
	{
		Gson gson = new Gson();
		return gson.toJson(lookup);
	}

	@SuppressWarnings("unchecked")
	public static ReceiverLookupDocument fromJSON(String value)
	{
		Gson gson = new Gson();
		return new ReceiverLookupDocument(gson.fromJson(value, Map.class));
	}
}