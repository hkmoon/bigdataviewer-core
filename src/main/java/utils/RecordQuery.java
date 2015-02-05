package utils;

import java.io.*;

/**
 * Created by moon on 2/4/15.
 */
public class RecordQuery
{
	private PrintWriter writer = null;
	private String filename = null;
	private static RecordQuery instance = null;

	static {
		RecordQuery.getInstance().setFilename( "/Users/moon/Documents/queries.txt" );
	}

	protected RecordQuery() {
		// Exists only to defeat instantiation.
	}

	protected void finalize() {
		if(writer != null)
		{
			writer.flush();
			writer.close();
		}
	}
	public static RecordQuery getInstance() {
		if(instance == null) {
			instance = new RecordQuery();
		}
		return instance;
	}

	public void setFilename(String str)
	{
		filename = str;
		try
		{
			writer = new PrintWriter( new FileOutputStream(filename),  true);
		}
		catch ( FileNotFoundException e )
		{
			e.printStackTrace();
		}
	}

	public void record(String str)
	{
		writer.write(str + "\n");
	}
}
