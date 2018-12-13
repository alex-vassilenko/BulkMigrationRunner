package com.indellient;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import javax.net.ssl.SSLContext;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.Scroll;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;

public class App implements Runnable 
{	
	// Source configuration
	private static final String SOURCE_HOST = System.getenv("SOURCE_HOST");
	private static final String SOURCE_USERNAME = System.getenv("SOURCE_USERNAME");
	private static final String SOURCE_PASSWORD = System.getenv("SOURCE_PASSWORD");
	private static final String SOURCE_INDEX = System.getenv("SOURCE_INDEX");
	private static final int    SOURCE_PORT = Integer.parseInt(System.getenv("SOURCE_PORT"));
	private static final String SOURCE_PROTOCOL = System.getenv("SOURCE_PROTOCOL");
	
	// Target configuration
	private static final String TARGET_HOST = System.getenv("TARGET_HOST");
	private static final String TARGET_USERNAME = System.getenv("TARGET_USERNAME");
	private static final String TARGET_PASSWORD = System.getenv("TARGET_PASSWORD");
	private static final String TARGET_INDEX = System.getenv("TARGET_INDEX");
	private static final String TARGET_MAPPING = System.getenv("TARGET_MAPPING");
	private static final String TARGET_JKSFILE = System.getenv("TARGET_JKSFILE");
	private static final String TARGET_JKSPASS = System.getenv("TARGET_JKSPASS");
	private static final int    TARGET_PORT = Integer.parseInt(System.getenv("TARGET_PORT"));
	private static final String TARGET_PROTOCOL = System.getenv("TARGET_PROTOCOL");
	
	// Ingestion configuration
	private static final int    SCROLL_SIZE = Integer.parseInt(System.getenv("SCROLL_SIZE"));
	private static final long   SCROLL_INTERVAL = Long.parseLong(System.getenv("SCROLL_INTERVAL"));
	private static final int    BULK_RETRY = 3;
	private static final String BULK_FILE = "failedDocs.txt";
	private static final int    HOLD_BACK = 30000;
	
	private App()
	{}
	
	public static void main(String[] args)
	{
		App app = new App();
		app.run();
	}

	public void run()
	{
		// Initialize clients (source and target)
		HttpHost sourceHost = new HttpHost(SOURCE_HOST, SOURCE_PORT, SOURCE_PROTOCOL);
		HttpHost targetHost = new HttpHost(TARGET_HOST, TARGET_PORT, TARGET_PROTOCOL);
		CredentialsProvider sourceProvider = addCredentials(SOURCE_USERNAME, SOURCE_PASSWORD);
		CredentialsProvider targetProvider = addCredentials(TARGET_USERNAME, TARGET_PASSWORD);
		RestHighLevelClient sourceClient = getSourceClient(sourceHost, sourceProvider);
		RestHighLevelClient targetClient = getTargetClient(targetHost, targetProvider);

		// Initialize Scroll request
		final Scroll scroll = new Scroll(TimeValue.timeValueSeconds(SCROLL_INTERVAL));
		SearchRequest searchRequest = new SearchRequest(SOURCE_INDEX);
		searchRequest.scroll(scroll);
		SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder()
				.query(QueryBuilders.matchAllQuery())
				.size(SCROLL_SIZE);
		searchRequest.source(searchSourceBuilder);
		SearchResponse searchResponse;
		
		// Scroll through
		try 
		{
			// Start measures
			int numDocuments = 0;
			long startTime = System.currentTimeMillis();
			
			// Get hits
			searchResponse = sourceClient.search(searchRequest);
			String scrollId = searchResponse.getScrollId();
			SearchHit[] searchHits = searchResponse.getHits().getHits();
			
			// Commit first batch
			BulkResponse response =
					commit(searchHits, targetClient, TARGET_INDEX, TARGET_MAPPING, BULK_RETRY);
			numDocuments += searchHits.length;
			printMessage(numDocuments, startTime);
			
			while (searchHits != null && searchHits.length > 0) 
			{
			    SearchScrollRequest scrollRequest = 
			    		new SearchScrollRequest(scrollId)
			    		.scroll(scroll);
			    searchResponse = sourceClient.searchScroll(scrollRequest);
			    scrollId = searchResponse.getScrollId();
			    searchHits = searchResponse.getHits().getHits();
			    if (searchHits.length == 0) 
			    {
			    	break;
			    }
			    response = 
			    		commit(searchHits, targetClient, TARGET_INDEX, TARGET_MAPPING, BULK_RETRY);
			    numDocuments += searchHits.length;
			    printMessage(numDocuments, startTime);
			}
			System.out.println("Bye, hope all you data is safe and sound.");
			
		} 
		catch (IOException | InterruptedException e) 
		{
			e.printStackTrace();
		} 
	}
	
	/**
	 * Commits a bulk request to ES
	 * @param searchHits
	 * @param client
	 * @param index
	 * @param mapping
	 * @return
	 * @throws InterruptedException 
	 * @throws IOException
	 */
	private BulkResponse commit(SearchHit[] searchHits, RestHighLevelClient client, 
			String index, String mapping, int retry) 
					throws InterruptedException
	{
		if (retry == 0)
		{
			System.out.println("Last resort: write to file.");
			writeBulkToFile(searchHits);
			return null;
		}
		BulkRequest bulkRequest = new BulkRequest();
		for (SearchHit hit : searchHits) 
		{
	    	String document = hit.getSourceAsString();
	    	IndexRequest indexRequest = new IndexRequest(index, mapping);
	    	indexRequest.source(document, XContentType.JSON);
	    	bulkRequest.add(indexRequest);
		}
		BulkResponse bulkResponse = null;
		try 
		{
			bulkResponse = client.bulk(bulkRequest);
			if (bulkResponse.hasFailures())
			{
				for (BulkItemResponse bulkItem : bulkResponse)
				{
					if (bulkItem.isFailed())
					{
						int requestId = bulkItem.getItemId();
						IndexRequest request = (IndexRequest) bulkRequest.requests().get(requestId);
						String requestDoc = request.source().utf8ToString();
						writeDocToFile(requestDoc);
					}
				}
			}
		} 
		catch (IOException e) 
		{
			System.out.println("# Retries: " + retry + " #");
			Thread.sleep(HOLD_BACK);
			commit(searchHits, client, index, mapping, --retry);
		}
		return bulkResponse;
	}
	
	private void writeBulkToFile(SearchHit[] searchHits) 
	{
		File file = new File(BULK_FILE);
		for (SearchHit hit : searchHits)
		{
			try 
			{
				FileWriter pw = new FileWriter(file, true);
				BufferedWriter bw = new BufferedWriter(pw);
				bw.write(hit.getSourceAsString());
				bw.newLine();
				bw.close();
			} 
			catch (IOException e) 
			{
				e.printStackTrace();
			}
		}
	}
	
	private void writeDocToFile(String doc) 
	{
		File file = new File(BULK_FILE);
		try 
		{
			FileWriter pw = new FileWriter(file, true);
			BufferedWriter bw = new BufferedWriter(pw);
			bw.write(doc);
			bw.newLine();
			bw.close();
		} 
		catch (IOException e) 
		{
			e.printStackTrace();
		}
	}
	
	/**
	 * Prepares a CredentialsProvider for the ES Clients
	 * @param user
	 * @param password
	 * @return
	 */
	private static CredentialsProvider addCredentials(String user, String password) 
	{
		CredentialsProvider provider = new BasicCredentialsProvider();
		UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(user, password);
		provider.setCredentials(AuthScope.ANY, credentials);
		return provider;
	}
	
	/**
	 * Prepares a client to connect to the source
	 * @param host
	 * @param provider
	 * @return
	 */
	private static RestHighLevelClient getSourceClient(HttpHost host, CredentialsProvider provider) 
	{
		RestClientBuilder builder = RestClient
				.builder(host)
				.setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder
		        .setDefaultCredentialsProvider(provider));
		return new RestHighLevelClient(builder);
	}
	
	/**
	 * Prepares a client to connect to the target
	 * @param host
	 * @param provider
	 * @return
	 */
	private static RestHighLevelClient getTargetClient(HttpHost host, CredentialsProvider provider) 
	{
		RestClientBuilder builder = RestClient
				.builder(host)
				.setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder
		        .setDefaultCredentialsProvider(provider)
		        .setSSLContext(getSSLContext(TARGET_JKSFILE, TARGET_JKSPASS)));
		return new RestHighLevelClient(builder);
	}
	
	/**
	 * Provides an SSL Context for connection
	 * @param jksFile
	 * @param jksPass
	 * @return
	 */
	private static SSLContext getSSLContext(String jksFile, String jksPass)
	{
		SSLContext sslContext = null;
		try 
		{
			sslContext = SSLContexts.custom()
			        .loadTrustMaterial(new File(jksFile), jksPass.toCharArray(),
			            new TrustSelfSignedStrategy())
			        .build();
		} 
		catch (Exception e) 
		{
			e.printStackTrace();
		}
		return sslContext;
	}
	
	/**
	 * Prints the sysout to keep track of number of records committed
	 * @param docCount
	 * @param startTime
	 * @param responseCode
	 */
	private static void printMessage(int docCount, long startTime) 
	{
		double timeSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
		String msg = "Document: " + docCount 
				+ " | Time: " + timeSeconds;
		System.out.println(msg);
	}
}
