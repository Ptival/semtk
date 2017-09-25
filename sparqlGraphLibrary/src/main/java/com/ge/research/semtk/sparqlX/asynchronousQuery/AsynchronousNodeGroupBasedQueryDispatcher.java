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

package com.ge.research.semtk.sparqlX.asynchronousQuery;

import java.net.ConnectException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;

import org.json.simple.JSONObject;

import com.ge.research.semtk.belmont.AutoGeneratedQueryTypes;
import com.ge.research.semtk.belmont.NodeGroup;
import com.ge.research.semtk.belmont.Returnable;
import com.ge.research.semtk.edc.client.EndpointNotFoundException;
import com.ge.research.semtk.edc.client.ResultsClient;
import com.ge.research.semtk.edc.client.StatusClient;
import com.ge.research.semtk.load.utility.SparqlGraphJson;
import com.ge.research.semtk.ontologyTools.OntologyInfo;
import com.ge.research.semtk.resultSet.GeneralResultSet;
import com.ge.research.semtk.resultSet.NodeGroupResultSet;
import com.ge.research.semtk.resultSet.Table;
import com.ge.research.semtk.resultSet.TableResultSet;
import com.ge.research.semtk.sparqlX.SparqlConnection;
import com.ge.research.semtk.sparqlX.SparqlEndpointInterface;
import com.ge.research.semtk.sparqlX.SparqlResultTypes;
import com.ge.research.semtk.sparqlX.client.SparqlQueryAuthClientConfig;
import com.ge.research.semtk.sparqlX.client.SparqlQueryClient;
import com.ge.research.semtk.sparqlX.client.SparqlQueryClientConfig;

public abstract class AsynchronousNodeGroupBasedQueryDispatcher {

	private static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
	
	protected NodeGroup queryNodeGroup;
	protected SparqlQueryClient retrievalClient; // used to get info on EDC service end points: the ones used to service EDC calls.
	protected ResultsClient resultsClient;
	protected StatusClient statusClient;
	
	protected SparqlEndpointInterface sei;

	protected String jobID;
	protected OntologyInfo oInfo;
	protected String domain;
	
	public AsynchronousNodeGroupBasedQueryDispatcher(String jobId, SparqlGraphJson sgJson, ResultsClient rClient, StatusClient sClient, SparqlQueryClient queryClient) throws Exception{
		this.jobID = jobId;
		
		this.resultsClient = rClient;
		this.statusClient = sClient;
		
		// get nodegroup and sei from json
		System.err.println("processing incoming nodegroup - in base class");

		System.err.println("about to get the nodegroup");
		this.queryNodeGroup = sgJson.getNodeGroup();

		System.err.println("about to get the default qry interface");
		this.sei = sgJson.getSparqlConn().getDefaultQueryInterface();
		
		SparqlConnection nodegroupConn = sgJson.getSparqlConn();
		this.domain = nodegroupConn.getDomain();
		
		if(queryClient.getConfig() instanceof SparqlQueryAuthClientConfig){
			System.err.println("Dispatcher exec config WAS an instance of the auth query client");
			
			SparqlQueryAuthClientConfig old = (SparqlQueryAuthClientConfig)queryClient.getConfig();
			this.oInfo = new OntologyInfo(old, nodegroupConn);
			
			SparqlQueryAuthClientConfig config = new SparqlQueryAuthClientConfig(	
					old.getServiceProtocol(),
					old.getServiceServer(), 
					old.getServicePort(), 
					old.getServiceEndpoint(),
					sei.getServerAndPort(),
					sei.getServerType(),
					sei.getDataset(),
					old.getSparqlServerUser(),
					old.getSparqlServerPassword());
			
			this.retrievalClient = new SparqlQueryClient(config );			
		}
		else{
			System.err.println("Dispatcher exec config WAS NOT an instance of the auth query client");
			
			this.oInfo = new OntologyInfo(queryClient.getConfig(), nodegroupConn);
			
			SparqlQueryClientConfig config = new SparqlQueryClientConfig(	
				queryClient.getConfig().getServiceProtocol(),
				queryClient.getConfig().getServiceServer(), 
				queryClient.getConfig().getServicePort(), 
				queryClient.getConfig().getServiceEndpoint(),
				sei.getServerAndPort(),
				sei.getServerType(),
				sei.getDataset());
		
		
			this.retrievalClient = new SparqlQueryClient(config );
		}
		
		this.updateStatus(0);
		
	}
	
	/**
	 * return the JobID. the clients will need this.
	 * @return
	 */
	public String getJobId(){
		return this.jobID;
	}
	
	public abstract TableResultSet execute(Object ExecutionSpecificConfigObject, DispatcherSupportedQueryTypes qt, String targetSparqlID) throws Exception;
	
	protected int updateRunStatus(float increment, int last, int ceiling) throws UnableToSetStatusException{
		int retval = ceiling;
		if(( last + increment) >= ceiling ){ retval =  ceiling;}
		else{
			this.updateStatus((int)(last + increment));
			retval = (int)(last + increment);
		}
		return retval;
	}
	
	/**
	 * send the collected results to the results service. 
	 * this will just return a true/false about whether the results were likely sent.
	 * @throws Exception 
	 * @throws EndpointNotFoundException 
	 * @throws ConnectException 
	 */
	protected void sendResultsToService(TableResultSet currResults) throws ConnectException, EndpointNotFoundException, Exception{
				
		HashMap<String, Integer> colInstCounter = new HashMap<String, Integer>();
			
		try{
			
			// repair column headers in the event that a duplicate header is encountered. by convention (established and existing only here), the first instance of a column name 
			// will remain unchanged, all future instances will be postfixed with "[X]" where X is the count encountered so far. this count will start at 1. 
			Table resTable = currResults.getTable();
			String[] unModColnames = resTable.getColumnNames(); 	// pre-modification column names
			String[] modColnames = new String[unModColnames.length];
			
			int posCount = 0;
			for(String uCol : unModColnames){
				if(colInstCounter.containsKey( uCol.toLowerCase() )){
					// seen this one already. update the counter and add it to the new header list.
					int update = colInstCounter.get( uCol.toLowerCase() ) + 1;
					colInstCounter.put( uCol.toLowerCase() , update);
					
					modColnames[posCount] = uCol + "[" + update + "]";
				}
				else{
					// never seen this column.
					modColnames[posCount] = uCol;
					// add to the hash
					colInstCounter.put( uCol.toLowerCase(), 0 );
				}
				
				posCount+=1;
			}
			resTable.replaceColumnNames(modColnames);
			
			this.resultsClient.execStoreTableResults(this.jobID, resTable);
		}
		catch(Exception eee){
			this.statusClient.execSetFailure("Failed to write results: " + eee.getMessage());
			eee.printStackTrace();
			throw new Exception("Unable to write results");
		}
	}
	
	
	private void sendResultsToService(NodeGroupResultSet preRet)  throws ConnectException, EndpointNotFoundException, Exception{
		try{
			JSONObject resJSON = preRet.getResultsJSON();
			this.resultsClient.execStoreGraphResults(this.jobID, resJSON);
		}
		catch(Exception eee){
			this.statusClient.execSetFailure("Failed to write results: " + eee.getMessage());
			eee.printStackTrace();
			throw new Exception("Unable to write results");
		}
	}

	
	/**
	 * send updates to the status service. 
	 * @param statusPercentNumber
	 * @throws UnableToSetStatusException
	 */
	protected void updateStatus(int statusPercentNumber) throws UnableToSetStatusException{
		
		try {
		// if statusPercentNumber >= 100, instead, set the success or failure.
			if(statusPercentNumber >= 100){
				this.statusClient.execSetSuccess();	
			}
			// else, try to set a value.
			else{
				this.statusClient.execSetPercentComplete(statusPercentNumber);
			}
			
		} catch (Exception e) {
			throw new UnableToSetStatusException(e.getMessage());
		}
	}
	
	
	/**
	 * the work failed. let the callers know via the status service. 
	 * @param rationale
	 * @throws UnableToSetStatusException
	 */
	protected void updateStatusToFailed(String rationale) throws UnableToSetStatusException{
		try{
			this.statusClient.execSetFailure(rationale != null ? rationale : "Exception with e.getMessage()==null");
			System.err.println("attempted to write failure message to status service");
		}
		catch(Exception eee){
			System.err.println("failed to write failure message to status service");
			throw new UnableToSetStatusException(eee.getMessage());
		}
	}
	
	/**
	 * used for testing the service/ probably not useful in practice
	 * @return
	 */
	public StatusClient getStatusClient(){ return this.statusClient;}
	/**
	 * used for testing the service/ probably not useful in practice
	 * @return
	 */
	public ResultsClient getResultsClient(){ return this.resultsClient;}
	
	public abstract String getConstraintType() throws Exception;
	
	public abstract String[] getConstraintVariableNames() throws Exception;
	
	public TableResultSet executePlainSparqlQuery(String sparqlQuery, DispatcherSupportedQueryTypes supportedQueryType) throws Exception{
		TableResultSet retval = null;
		SparqlQueryClient nodegroupQueryClient = this.retrievalClient;
		
		try{

			Calendar cal = Calendar.getInstance();
			System.err.println("Job " + this.jobID + ": AsynchronousNodeGroupExecutor start @ " + DATE_FORMAT.format(cal.getTime()));

			System.err.println("Sparql Query to execute: ");
			System.err.println(sparqlQuery);
			
			// run the actual query and get a result. 
			GeneralResultSet preRet = null;
			
			if(supportedQueryType == DispatcherSupportedQueryTypes.CONSTRUCT){
				// constructs require particular support for a different result set.
				preRet = nodegroupQueryClient.execute(sparqlQuery, SparqlResultTypes.GRAPH_JSONLD);
				retval = new TableResultSet(true);
			}
			else{
				// all other types
				preRet = nodegroupQueryClient.execute(sparqlQuery, SparqlResultTypes.TABLE);
				retval = (TableResultSet) preRet;
			}

			
			if (retval.getSuccess()) {
				
				System.err.println("about to write results for " + this.jobID);
				if(supportedQueryType == DispatcherSupportedQueryTypes.CONSTRUCT){
					// constructs require particular support in the results client and the results service. this support would start here.
					this.sendResultsToService((NodeGroupResultSet) preRet);
				}
				else {
					// all other types
					System.err.println("Query returned " + retval.getTable().getNumRows() + " results.");
					this.sendResultsToService(retval);
				}
				this.updateStatus(100);		// work's done
			}
			else {
				this.updateStatusToFailed("Query client returned error to dispatch client: \n" + retval.getRationaleAsString("\n"));
			}
			
			cal = Calendar.getInstance();
			System.err.println("Job " + this.jobID + ": AsynchronousNodeGroupExecutor end   @ " + DATE_FORMAT.format(cal.getTime()));

		}
		catch(Exception e){
			// something went awry. set the job to failure. 
			this.updateStatusToFailed(e.getMessage());
			e.printStackTrace();
			throw new Exception("Query failed: " + e.getMessage() );
		}
		
		return retval;
	}

	protected String getSparqlQuery(DispatcherSupportedQueryTypes qt, String targetSparqlID) throws Exception{
		String retval = null;
		
		// select 
		if(qt.equals(DispatcherSupportedQueryTypes.SELECT_DISTINCT)){
			retval = this.queryNodeGroup.generateSparql(AutoGeneratedQueryTypes.QUERY_DISTINCT, false, null, null);
		}
		else if(qt.equals(DispatcherSupportedQueryTypes.COUNT)){
			retval = this.queryNodeGroup.generateSparql(AutoGeneratedQueryTypes.QUERY_COUNT, false, null, null);
		}
		else if(qt.equals(DispatcherSupportedQueryTypes.FILTERCONSTRAINT)){
			
			// find our returnable and pass it on.
			Returnable rt = null;
			rt = this.queryNodeGroup.getNodeBySparqlID(targetSparqlID);
			if(rt == null){
				rt = this.queryNodeGroup.getPropertyItemBySparqlID(targetSparqlID);
			}
			
			// use the rt
			retval = this.queryNodeGroup.generateSparql(AutoGeneratedQueryTypes.QUERY_CONSTRAINT, false, null, rt);
		}
		else if(qt.equals(DispatcherSupportedQueryTypes.CONSTRUCT)){
			retval = this.queryNodeGroup.generateSparqlConstruct();
		}
		else if(qt.equals(DispatcherSupportedQueryTypes.DELETE)) {
			retval = this.queryNodeGroup.generateSparqlDelete(null);
		}
		
		else{
			// never seen this one. panic.
			throw new Exception("Dispatcher passed and unrecognized query type. it does not know how to build a " + qt.name() +  " query");
		}
		
		return retval;
	}

}
