/**
 ** Copyright 2016 General Electric Company
 **
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 ** 
 **     http://www.apache.org/licenses/LICENSE-2.0
 ** 
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.ge.research.semtk.test;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.lang.StringEscapeUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.ge.research.semtk.belmont.AutoGeneratedQueryTypes;
import com.ge.research.semtk.belmont.NodeGroup;
import com.ge.research.semtk.load.DataLoader;
import com.ge.research.semtk.load.dataset.CSVDataset;
import com.ge.research.semtk.load.dataset.Dataset;
import com.ge.research.semtk.load.utility.SparqlGraphJson;
import com.ge.research.semtk.properties.EndpointProperties;
import com.ge.research.semtk.resultSet.GeneralResultSet;
import com.ge.research.semtk.resultSet.SimpleResultSet;
import com.ge.research.semtk.resultSet.Table;
import com.ge.research.semtk.resultSet.TableResultSet;
import com.ge.research.semtk.sparqlX.NeptuneSparqlEndpointInterface;
import com.ge.research.semtk.sparqlX.SparqlConnection;
import com.ge.research.semtk.sparqlX.SparqlEndpointInterface;
import com.ge.research.semtk.sparqlX.SparqlResultTypes;
import com.ge.research.semtk.sparqlX.SparqlToXUtils;
import com.ge.research.semtk.sparqlX.VirtuosoSparqlEndpointInterface;
import com.ge.research.semtk.utility.LocalLogger;
import com.ge.research.semtk.utility.Utility;

/**
 * A utility class to load data to a semantic graph.  Intended for use in tests.
 * 
 * NOTE: This class cannot be put in under src/test/java because it must remain accessible to other projects.
 */
public class TestGraph {

	// PEC TODO: if we want the option of sharing or splitting model from data in different graphs then static functions will not do.
	//           It will have to be an object instantiated by a json nodegroup (shows whether they're split) or NULL (pick a default)
	public TestGraph() {
	}
	
	// PEC TODO:  specify model or data graph
	public static SparqlEndpointInterface getSei() throws Exception {
		SparqlEndpointInterface sei = SparqlEndpointInterface.getInstance(getSparqlServerType(), getSparqlServer(), generateDatasetName("both"), getUsername(), getPassword());
		
		try{
			sei.executeTestQuery();
		}catch(Exception e){
			LocalLogger.logToStdOut("***** Cannot connect to " + getSparqlServerType() + " server at " + getSparqlServer() + " with the given credentials for '" + getUsername() + "'.  Set up this server or change settings in TestGraph. *****");
			throw e;
		}
		
		if (sei instanceof NeptuneSparqlEndpointInterface) {
			((NeptuneSparqlEndpointInterface) sei).setS3Config(IntegrationTestUtility.getS3Config());
		}
		return sei;
	}
	
	public static EndpointProperties getEndpointProperties() throws Exception {
		SparqlEndpointInterface sei = getSei();
		EndpointProperties ret = new EndpointProperties();
		ret.setJobEndpointType(sei.getServerType());
		ret.setJobEndpointServerUrl(sei.getServerAndPort());
		ret.setJobEndpointUsername(sei.getUserName());
		ret.setJobEndpointPassword(sei.getPassword());
		
		return ret;
	}
	
	public static SparqlConnection getSparqlConn(String domain) throws Exception {
		SparqlConnection conn = new SparqlConnection();
		conn.setName("JUnitTest");
		conn.setDomain(domain);
		
		SparqlEndpointInterface sei = getSei();
		conn.addDataInterface( sei.getServerType(), sei.getServerAndPort(), sei.getGraph());
		conn.addModelInterface(sei.getServerType(), sei.getServerAndPort(), sei.getGraph());
		return conn;
	}
	
	/**
	 * Get the URL of the SPARQL server.
	 * @throws Exception 
	 */
	public static String getSparqlServer() throws Exception{
		return IntegrationTestUtility.getSparqlServer();
	}
	
	/**
	 * Get the test dataset name.
	 */
	public static String getDataset() throws Exception {
		return getSei().getGraph();
	}
	
	/**
	 * Get the SPARQL server type.
	 * @throws Exception 
	 */
	public static String getSparqlServerType() throws Exception{
		return IntegrationTestUtility.getSparqlServerType();
	}
	
	/**
	 * Get the SPARQL server username.
	 * @throws Exception 
	 */
	public static String getUsername() throws Exception{
		return IntegrationTestUtility.getSparqlServerUsername();
	}
	
	/**
	 * Get the SPARQL server password.
	 * @throws Exception 
	 */
	public static String getPassword() throws Exception{
		return IntegrationTestUtility.getSparqlServerPassword();
	}
	
	/**
	 * Clear the test graph
	 */
	// PEC TODO:  clear model and data graph
	public static void clearGraph() throws Exception {
		getSei().clearGraph();
		IntegrationTestUtility.getOntologyInfoClient().uncacheOntology(TestGraph.getSparqlConn("http"));
	}
	
	public static void clearPrefix(String prefix) throws Exception {
		getSei().clearPrefix(prefix);
	}
	/**
	 * Drop the test graph (DROP lets the graph be CREATEd again, whereas CLEAR does not)
	 */
	public static void dropGraph() throws Exception {
		getSei().dropGraph();
	}
	
	public static void execDeletionQuery(String query) throws Exception{
		SparqlEndpointInterface sei = getSei();
		GeneralResultSet resultSet = sei.executeQueryAndBuildResultSet(query, SparqlResultTypes.CONFIRM);
		if (!resultSet.getSuccess()) {
			throw new Exception(resultSet.getRationaleAsString(" "));
		}		
	}
	
	public static Table execTableSelect(String query) throws Exception {
		// execute a select query
		// exception if there's any problem
		// return the table
		SparqlEndpointInterface sei = getSei();
		TableResultSet res = (TableResultSet) sei.executeQueryAndBuildResultSet(query, SparqlResultTypes.TABLE);
		res.throwExceptionIfUnsuccessful();
		
		return res.getResults();
	}
	
	/**
	 * Get the number of triples in the test graph.
	 */
	public static int getNumTriples() throws Exception {
		String sparql = SparqlToXUtils.generateCountTriplesSparql(getSei());
		Table table = TestGraph.execTableSelect(sparql); 
		return (new Integer(table.getCell(0, 0))).intValue(); // this cell contains the count
	}
	
	/**
	 * Upload owl file to the test graph
	 * @param owlFilename  "src/test/resources/file.owl"
	 * @throws Exception
	 */
	// PEC TODO:  specify model or data graph
	public static void uploadOwl(String owlFilename) throws Exception {
		
		SparqlEndpointInterface sei = getSei();		
		Path path = Paths.get(owlFilename);
		byte[] owl = Files.readAllBytes(path);
		SimpleResultSet resultSet = SimpleResultSet.fromJson(sei.executeAuthUploadOwl(owl));
		if (!resultSet.getSuccess()) {
			throw new Exception(resultSet.getRationaleAsString(" "));
		}
	}
	
	/**
	 * Find graph from inside the owl/rdf
	 * Clear the graph and load the owl
	 * @param owlFilename
	 * @throws Exception
	 */
	public static void syncOwlToItsGraph(String owlFilename) throws Exception {
		
		String base = Utility.getXmlBaseFromOwlRdf(new FileInputStream(owlFilename));
		
		SparqlEndpointInterface sei = getSei();
		
		sei.setGraph(base);
		sei.clearGraph();
		
		Path path = Paths.get(owlFilename);
		byte[] owl = Files.readAllBytes(path);
		SimpleResultSet resultSet = SimpleResultSet.fromJson(sei.executeAuthUploadOwl(owl));
		if (!resultSet.getSuccess()) {
			throw new Exception(resultSet.getRationaleAsString(" "));
		}
	}
	
	public static void uploadTurtle(String owlFilename) throws Exception {
		
		SparqlEndpointInterface sei = getSei();
		Path path = Paths.get(owlFilename);
		byte[] owl = Files.readAllBytes(path);
		SimpleResultSet resultSet = SimpleResultSet.fromJson(sei.executeAuthUploadTurtle(owl));
		if (!resultSet.getSuccess()) {
			throw new Exception(resultSet.getRationaleAsString(" "));
		}
	}
	
	/**
	 * Upload an owl string to the test graph
	 * @param owl
	 * @throws Exception
	 */
	public static void uploadOwlString(String owl) throws Exception {
		
		SparqlEndpointInterface sei = getSei();
		
		SimpleResultSet resultSet = SimpleResultSet.fromJson(sei.executeAuthUploadOwl(owl.getBytes()));
		if (!resultSet.getSuccess()) {
			throw new Exception(resultSet.getRationaleAsString(" "));
		}
	}
	
	/**
	 * 
	 * @param jsonFilename e.g. "src/test/resources/nodegroup.json"
	 * @return
	 * @throws Exception
	 */
	public static NodeGroup getNodeGroup(String jsonFilename) throws Exception {
		
		return getSparqlGraphJsonFromFile(jsonFilename).getNodeGroup();
	}
	
	/**
	 * Get SparqlGraphJson modified with Test connection
	 * @param jsonFilename
	 */
	public static SparqlGraphJson getSparqlGraphJsonFromFile(String jsonFilename) throws Exception {		
		InputStreamReader reader = new InputStreamReader(new FileInputStream(jsonFilename));
		JSONObject jObj = (JSONObject) (new JSONParser()).parse(reader);		
		return getSparqlGraphJsonFromJson(jObj);
	}	
	
	/**
	 * Get SparqlGraphJson modified with Test connection
	 * @param jsonString
	 */
	public static SparqlGraphJson getSparqlGraphJsonFromString(String jsonString) throws Exception {		
		JSONObject jObj = (JSONObject) (new JSONParser()).parse(jsonString);		
		return getSparqlGraphJsonFromJson(jObj);
	}	
	
	/**
	 * Get SparqlGraphJson modified with Test connection.
	 * If owl imports are enabled, only swaps the data connection.
	 * @param jsonObject
	 */	
	@SuppressWarnings("unchecked")
	public static SparqlGraphJson getSparqlGraphJsonFromJson(JSONObject jObj) throws Exception{
		
		SparqlGraphJson s = new SparqlGraphJson(jObj);
		
		// swap out data interfaces
		SparqlConnection conn = s.getSparqlConn();
		conn.clearDataInterfaces();
		conn.addDataInterface(getSei());
		
		// swap out model interfaces
		if (! conn.isOwlImportsEnabled()) {
			conn.clearModelInterfaces();
			conn.addModelInterface(getSei());
		}
		s.setSparqlConn(conn);
		
		return s;
	}
	
	/**
	 * Generate a dataset name (unique per user)
	 */
	public static String generateGraphName(String sub) {
		return generateDatasetName(sub);
	}
	public static String generateDatasetName(String sub) {
		String user = System.getProperty("user.name");
		
		String machine = null;
		try {
			machine = java.net.InetAddress.getLocalHost().getHostName().replaceAll("[^a-zA-Z0-9-]", "_");
		} catch (Exception e) {
			machine = "unknown_host";
		}
		
		return String.format("http://junit/%s/%s/%s", machine, user, sub);
	}	
	
	/**
	 * Clear graph, 
	 * In src/test/resources: loads baseName.owl, gets sgJson from baseName.json, and loads baseName.csv
	 * @param baseName
	 * @return SparqlGraphJson
	 * @throws Exception
	 */
	public static SparqlGraphJson initGraphWithData(String baseName) throws Exception {
		String jsonPath = String.format("src/test/resources/%s.json", baseName);
		String owlPath = String.format("src/test/resources/%s.owl", baseName);
		String csvPath = String.format("src/test/resources/%s.csv", baseName);

		// load the model
		TestGraph.clearGraph();
		TestGraph.uploadOwl(owlPath);
		
		return ingest(jsonPath, csvPath);
	}
	
	public static SparqlGraphJson ingest(String jsonPath, String csvPath) throws Exception {

		SparqlGraphJson sgJson = TestGraph.getSparqlGraphJsonFromFile(jsonPath); 
		
		// load the data
		Dataset ds = new CSVDataset(csvPath, false);
		DataLoader dl = new DataLoader(sgJson, 2, ds, getUsername(), getPassword());
		dl.importData(true);
		
		return sgJson;
	}
	
	public static Table runQuery(String query) throws Exception {
		SparqlEndpointInterface sei = TestGraph.getSei();
		TableResultSet res = (TableResultSet) sei.executeQueryAndBuildResultSet(query, SparqlResultTypes.TABLE);
		if (!res.getSuccess()) {
			throw new Exception("Failure querying: " + query + " \nrationale: "	+ res.getRationaleAsString(" "));
		}
		Table tab = res.getTable();
		return tab;
	}
	
	/**
	 * execute ng select and compare results to those in expectedFileName
	 * @param ng
	 * @param caller - whose resources should be checked for expectedFileName
	 * @param expectedFileName
	 * @throws Exception
	 */
	public static void queryAndCheckResults(NodeGroup ng, Object caller, String expectedFileName) throws Exception {
		// get all the answers, changing "\r\n" to simply "\n"
		String query = ng.generateSparql(AutoGeneratedQueryTypes.QUERY_DISTINCT, false, 0, null);		
		Table tab = TestGraph.runQuery(query);
		String actual =   tab.toCSVString();
		TestGraph.compareResults(actual, caller, expectedFileName);
	}
	
	/**
	 * Compare results to those in expectedFileName
	 * @param results\
	 * @param caller - whose resources should be checked for expectedFileName
	 * @param expectedFileName
	 * @throws Exception 
	 */
	public static void compareResultsOLD(String results, Object caller, String expectedFileName) throws Exception {
		String actual =  results.replaceAll("\r\n", "\n");
		String expected = null;
		try {
			expected = Utility.getResourceAsString(caller, expectedFileName).replaceAll("\r\n", "\n");
		} catch (Exception e) {
			throw new Exception ("Error retrieving file: " + expectedFileName, e);
		}
		
		actual = Utility.replaceUUIDs(actual);
		expected = Utility.replaceUUIDs(expected);
				
		// if not equal, print them out
		if (! actual.equals(expected)) {
			System.out.print("Expected:\n--------\n" + StringEscapeUtils.escapeJava(expected) + "\n--------\n");
			System.out.print("Actual:\n--------\n" + StringEscapeUtils.escapeJava(actual) + "\n--------\n");
			
			// print message if lengths are different
			int actualLen = actual.length();
			int expectedLen = expected.length();
			
			if (actualLen != expectedLen) {
				System.out.print(String.format("Expected len: %d.  Actual len: %d\n", expectedLen, actualLen));
			}
			
			// print first 10 differences
			int found = 0;
			for (int i=0; found < 10 && i < Math.min(actualLen, expectedLen); i++) {
				if (actual.charAt(i) != expected.charAt(i)) {
					found ++;
					System.out.print(String.format("Char %d: expected: '%c' actual: '%c'\n", i, expected.charAt(i), actual.charAt(i)));
				}
			}
			
			// fail
			assertTrue("Actual results did not match expected (see stdout)", false);
		}
	}
	
	public static void compareResults(String actual, Object caller, String expectedFileName) throws Exception {
		String expected = null;
		
		try {
			expected = Utility.getResourceAsString(caller, expectedFileName);
		} catch (Exception e) {
			throw new Exception ("Error retrieving file: " + expectedFileName, e);
		}
		
		String actualLines[] = actual.split("\\r?\\n");
		String expectedLines[] = expected.split("\\r?\\n");
		
		for (int i=0; i < expectedLines.length; i++) {
			String actualCells[] = actualLines[i].split("\\s*,\\s*");
			String expectedCells[] = expectedLines[i].split("\\s*,\\s*");
			
			for (int j=0; j < expectedCells.length; j++) {
				// change any GUIDs to <UUID>
				// so <UUID> may also appear in expected results already 'converted'
				String actualVal = Utility.replaceUUIDs(actualCells[j]);
				String expectedVal = Utility.replaceUUIDs(expectedCells[j]);
				
				// cheap shot at handling dates with and without milliseconds
				if (actualVal.endsWith(".000Z") && !expectedVal.endsWith(".000Z")) {
					expectedVal += ".000Z";
				}
				
				// cheap shot at allowing Neptune to add a URI prefix to belmont/generateSparqlInsert#uri
				if (expectedVal.startsWith("belmont/generateSparqlInsert#")) {
						actualVal = actualVal.replaceFirst("^.*/belmont", "belmont");
				}
				
				if (! actualVal.equals(expectedVal)) {
					assertTrue("At return line " + String.valueOf(i) + " expected val '" + expectedVal + "' did not match actual '" + actualVal + "'", false);
				}
			}
		}
	}

}
