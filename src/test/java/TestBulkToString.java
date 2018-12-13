import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class TestBulkToString 
{
	private static final int RETRY = 3;
	private static final String[] DOCS = {"ES doc 1", "ES Doc 2", "ES Doc 3", "ES Doc 4"};

	public static void main(String[] args) throws IOException 
	{
		retry(RETRY);
		retry(RETRY);
	}
	
	private static void retry(int retry) throws IOException
	{
		if (retry == 0)
		{
			System.out.println("Done!");
			writeToFile(DOCS);
			return;
		}
		System.out.println("#### Processing failed ####");
		System.out.println("Retrying: " + retry);
		retry(--retry);
	}
	
	private static void writeToFile(String[] docs) throws IOException 
	{
		File file = new File("failedDocs.txt");
		for (String doc : docs)
		{
			try 
			{
				FileWriter pw = new FileWriter(file, true);
				BufferedWriter bw = new BufferedWriter(pw);
				bw.write(doc);
				bw.newLine();
				bw.close();
			}
			catch (FileNotFoundException e) 
			{
				e.printStackTrace();
			}
		}
	}
}
