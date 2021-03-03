/**
 ** Copyright 2016-2020 General Electric Company
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
package com.ge.research.semtk.sparqlX.dispatch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.ge.research.semtk.belmont.AutoGeneratedQueryTypes;
import com.ge.research.semtk.belmont.NodeGroup;
import com.ge.research.semtk.belmont.Returnable;
import com.ge.research.semtk.edc.JobTracker;
import com.ge.research.semtk.edc.client.OntologyInfoClient;
import com.ge.research.semtk.edc.client.ResultsClient;
import com.ge.research.semtk.edc.client.ResultsClientConfig;
import com.ge.research.semtk.edc.client.StatusClient;
import com.ge.research.semtk.load.utility.SparqlGraphJson;
import com.ge.research.semtk.nodeGroupStore.client.NodeGroupStoreRestClient;
import com.ge.research.semtk.querygen.Query;
import com.ge.research.semtk.querygen.QueryList;
import com.ge.research.semtk.querygen.client.QueryExecuteClient;
import com.ge.research.semtk.querygen.client.QueryGenClient;
import com.ge.research.semtk.resultSet.Table;
import com.ge.research.semtk.resultSet.TableResultSet;
import com.ge.research.semtk.sparqlX.SparqlEndpointInterface;
import com.ge.research.semtk.sparqlX.SparqlResultTypes;
import com.ge.research.semtk.sparqlX.dispatch.QueryGroup.DispatchQueryGroup;
import com.ge.research.semtk.sparqlX.dispatch.QueryGroup.QueryGroupCollection;
import com.ge.research.semtk.sparqlX.asynchronousQuery.*;
import com.ge.research.semtk.utility.LocalLogger;
import com.ge.research.semtk.utilityge.Utility;

public class EdcDispatcher extends AsynchronousNodeGroupBasedQueryDispatcher {
	
	protected static final int MAX_NUMBER_SIMULTANEOUS_QUERIES_PER_USER = 50;  // maybe move this to a configured value?
	
	DispatchServiceManager dispatchServiceMgr; 
	QueryGroupCollection queryGroupColl;
	
	JSONObject constraints = null;	// used for eventual handling of the constraints.
	
	// table column names/types that must be output by query generator, for use by the query executor
	public final static String[] EDC_DISPATCHER_COL_NAMES = {Utility.COL_NAME_UUID, Utility.COL_NAME_QUERY, Utility.COL_NAME_CONFIGJSON};
	public final static String[] EDC_DISPATCHER_COL_TYPES = {"String","String","String"};											   	
	
	/**
	 * create a new instance of the dispatcher.
	 * @param encodedNodeGroup
	 * @throws Exception
	 */
	public EdcDispatcher(String jobId, SparqlGraphJson sgJson, SparqlEndpointInterface jobTrackerSei, ResultsClientConfig resConfig, SparqlEndpointInterface extConfigSei, boolean heedRestrictions, OntologyInfoClient oInfoClient, NodeGroupStoreRestClient ngStoreClient) throws Exception{
	
		super(jobId, sgJson, jobTrackerSei, resConfig, extConfigSei, false, oInfoClient, ngStoreClient);
		
		this.dispatchServiceMgr =  new DispatchServiceManager(extConfigSei, this.queryNodeGroup, oInfo, domain, this.querySei, oInfoClient, heedRestrictions);
	
	}

	/**
	 * 
	 * 
	 * @throws Exception 
	 */
	@Override
	public void execute(Object extConstraintsJsonObj, Object flagsObj, DispatcherSupportedQueryTypes qt, String targetSparqlID) {
		
		try{
			JSONObject extConstraintsJson = (JSONObject) extConstraintsJsonObj;
			QueryFlags flags = (QueryFlags) flagsObj;
		
			LocalLogger.logToStdOut("Job " + this.jobID + " dispatch...");
			long startTimeMillis = System.currentTimeMillis();

			if (this.dispatchServiceMgr.getServiceMnemonic() == null) {
				String sparqlQuery = this.getSparqlQuery(qt, targetSparqlID);
				this.executePlainSparqlQuery(sparqlQuery, qt);

			} else if( qt.equals(DispatcherSupportedQueryTypes.SELECT_DISTINCT)) {
				// edc-specific behavior
				this.executeEdcSelect(extConstraintsJson, flags);
			}
			else if( qt.equals(DispatcherSupportedQueryTypes.FILTERCONSTRAINT)) { 
				this.executeEdcFilterConstraint(targetSparqlID);
			}
			else{
				throw new Exception("the query type " + qt.name() + " is not supported for EDC queries.");
			}

			/* PEC NOTE
			 * I removed COUNT query support because 
			 * 		- this code is already too confusing
			 *      - not sure why you'd go through all the trouble to execute EDC and then throw it away for a count
			 *      - not used anywhere I'm aware of
			 */
			
			// if the query was a count, we need to support that by counting the results from EDC. it is a bit fringe but worth implmenting.
//			if(qt.equals(DispatcherSupportedQueryTypes.COUNT)){
//				// interestingly, this is an intermediate result. we must now take its count, then throw it away
//
//				ArrayList<ArrayList<String>> rows = new ArrayList<ArrayList<String>>();
//				String[] cols = {"count"};
//				String[] types = {"integer"};
//				ArrayList<String> OnlyRow = new ArrayList<String>();
//
//				OnlyRow.add(retval.getTable().getNumRows() + "" );
//				rows.add(OnlyRow);
//				Table trueRetTable = new Table(cols, types, rows);
//
//				retval = new TableResultSet(true);
//				retval.addResults(trueRetTable);
//			}

			LocalLogger.logToStdOut("Job " + this.jobID + " dispatch completed in " + com.ge.research.semtk.utility.Utility.getSecondsSince(startTimeMillis) + " sec (total)");

		}
		catch(Exception e){
			// something went awry. set the job to failure. 
			this.updateStatusToFailed(e.getMessage());
			LocalLogger.printStackTrace(e);
		}
	}
	
	private TableResultSet executeEdcFilterConstraint(String targetSparqlID) throws Exception {
		TableResultSet retval = null;
		NodeGroup ng = this.dispatchServiceMgr.getFilterNodegroup();
		Returnable targetObj = ng.getItemBySparqlID(targetSparqlID);
		if (targetObj == null) {
			throw new Exception("Can't find item in nodegroup with SPARQL id: " + targetSparqlID);
		}
		String sparql = ng.generateSparql(AutoGeneratedQueryTypes.QUERY_CONSTRAINT, false, -1, targetObj);
		this.executePlainSparqlQuery(sparql, DispatcherSupportedQueryTypes.SELECT_DISTINCT);
		return retval;

	}
	
	/**
	 * Run and EDC select query
	 * @param extConstraintsJson
	 * @param flags
	 * @return
	 * @throws Exception
	 */
	private TableResultSet executeEdcSelect(JSONObject extConstraintsJson, QueryFlags flags) throws Exception{
		TableResultSet retval = null;
		long startTimeMillis;
		
		try{

			// get service info and augmented nodegroup from dispatch service manager
			LocalLogger.logToStdOut("Job " + this.jobID + ": dispatch service manager");
			startTimeMillis = System.currentTimeMillis();
			NodeGroup edcNodegroup = this.dispatchServiceMgr.getEdcNodegroup();
			QueryGenClient edcGenerateClient = this.dispatchServiceMgr.getGenerateClient();
			LocalLogger.logToStdOut("Job " + this.jobID + ": dispatch service manager completed in " + com.ge.research.semtk.utility.Utility.getSecondsSince(startTimeMillis) + " sec");
			
			// get the table to send to the query generation service
			LocalLogger.logToStdOut("Job " + this.jobID + ": prep binning");
			startTimeMillis = System.currentTimeMillis();
			Table locationAndValueTable = this.performEdcPrepBinning(edcNodegroup);	
			LocalLogger.logToStdOut("Job " + this.jobID + ": prep binning completed in " + com.ge.research.semtk.utility.Utility.getSecondsSince(startTimeMillis) + " sec");
			
			if(locationAndValueTable.getNumRows() == 0) {
				// handle empty results
				retval = new TableResultSet(true);
				retval.addResults(new Table(new String[]{}, new String [] {}));
			} else {
			
				// update the status 
				this.updateStatus(10);
				
				// generate queries
				LocalLogger.logToStdOut("Job " + this.jobID + ": generate external queries");
				startTimeMillis = System.currentTimeMillis();
				TableResultSet queryGenResultSet = edcGenerateClient.execute(locationAndValueTable,extConstraintsJson,flags);
				if(!queryGenResultSet.getSuccess()){
					throw new Exception("Could not generate queries: " + queryGenResultSet.getRationaleAsString(","));
				}
				Table queryGenResultTable = queryGenResultSet.getResults();			
				LocalLogger.logToStdOut(queryGenResultTable.toCSVString());
				LocalLogger.logToStdOut("Job " + this.jobID + ": generate external queries completed in " + com.ge.research.semtk.utility.Utility.getSecondsSince(startTimeMillis) + " sec");	
				retval = new TableResultSet(true);
				
				if (flags != null && flags.isSet(AsynchronousNodeGroupBasedQueryDispatcher.FLAG_DISPATCH_RETURN_QUERIES)) {
					this.updateStatus(60);
					Table queryTable = queryGenResultSet.getTable();
					queryTable.removeColumn("UUID");
					queryTable.insertColumn("EdcMnemonic", "String", 0, this.dispatchServiceMgr.getServiceMnemonic());
					retval.addResults(queryTable);
					
				} else {
					// update the status
					this.updateStatus(20);
					
					// execute queries
					LocalLogger.logToStdOut("Job " + this.jobID + ": execute external queries");
					startTimeMillis = System.currentTimeMillis();
					this.runEdcThreads(queryGenResultTable, 20, 70);
					LocalLogger.logToStdOut("Job " + this.jobID + ": execute external queries completed in " + com.ge.research.semtk.utility.Utility.getSecondsSince(startTimeMillis) + " sec");
					
					// update status
					this.updateStatus(75);
					
					// merge results
					LocalLogger.logToStdOut("Job " + this.jobID + ": fuse results");	
					startTimeMillis = System.currentTimeMillis();
					String[] columnNamesInOrder = this.queryGroupColl.getColumnNames();
					String[] columnTypesInNameOrder = this.getColumnTypes(columnNamesInOrder);  // TODO: figure out how to get column types. they are elusive...
					Table retTable = this.fuseResults(columnNamesInOrder, columnTypesInNameOrder);
					LocalLogger.logToStdOut("Job " + this.jobID + ": fuse results completed in " + com.ge.research.semtk.utility.Utility.getSecondsSince(startTimeMillis) + " sec");
					
					LocalLogger.logToStdOut("Job " + this.jobID + ": add results to table");	
					startTimeMillis = System.currentTimeMillis();
					retval.addResults(retTable);    // this is a slow line because it involves making potentially a lot of JSON never used in this case.
					LocalLogger.logToStdOut("Job " + this.jobID + ": add results to table completed in " + com.ge.research.semtk.utility.Utility.getSecondsSince(startTimeMillis) + " sec");
				}
			}
			this.updateStatus(95);		

			// send to results service
			LocalLogger.logToStdOut("Job " + this.jobID + ": write results");	
			startTimeMillis = System.currentTimeMillis();
			this.sendResultsToService(retval); 			
			LocalLogger.logToStdOut("Job " + this.jobID + ": write results completed in " + com.ge.research.semtk.utility.Utility.getSecondsSince(startTimeMillis) + " sec");
			
			this.updateStatus(100);		

		}catch(Exception e){
				// something went awry. set the job to failure. 
				this.updateStatusToFailed(e.getMessage());
				throw e;
		}
		
		return retval;
	}
	
	/** 
	 * execute the threads to be completed. periodically, update the status
	 * @param queryGenTable
	 * @return
	 * @throws Exception 
	 */
	private int runEdcThreads(Table queryGenTable, int currentStatus, int ceiling) throws Exception{
		
		if(queryGenTable.getNumRows() == 0){
			throw new Exception("Query generator indicates no work to perform");
		}
		
		// note (for updates to the status service, we have a range of 50 percentage units to play with: 20% to 70%
		float increment = 50 / queryGenTable.getNumRows();
		if(increment < 1){ increment = 1; }
		int highestStatPercent = ceiling;
		
		int numberThreadsCompletedSuccessfully = 0;
		String queryExecClientName = "unknown";
		// array of entries to determine success count. 
		Exception[] threadExceptionArr = new Exception[queryGenTable.getNumRows()];
		
		LocalLogger.logToStdErr("About to start edc work");
		
		// create the group of threads 
		int numQueries = queryGenTable.getNumRows();   // the total we will try.
		DispatcherWorkThread[] spunup = new DispatcherWorkThread[numQueries]; // the array of them. 
		try{
			int threadLimit = MAX_NUMBER_SIMULTANEOUS_QUERIES_PER_USER;
			int finished = 0;
			int iteration = 0;
			int running = 0;
			int currentReq = 0;
			while(finished < numQueries){
				if(running == numQueries){
					// nothing left to do... just wait for them to finish. 
					break;
				}
				if(running >= threadLimit || currentReq >= numQueries){
					for(int joined = 0; joined < threadLimit; joined += 1){
						try {
							spunup[joined + (threadLimit * iteration)].join();
							finished += 1;
							running = running - 1;
							this.jobTracker.incrementPercentComplete(this.jobID, Math.round(increment), highestStatPercent);
							if(finished >= numQueries){break;}
						} catch (Exception e) {
							LocalLogger.printStackTrace(e);
							throw new IOException("(Join failure in dispatch threading : joined value was "+ joined + ":: " + e.getClass().toString() + " : "+ e.getMessage() + ")");
						}
					}
					if(currentReq%threadLimit == 0){ iteration += 1;}
				}
				else{
					if (currentReq < numQueries) {
						try {
							// get the right DispatchQueryGroup
							DispatchQueryGroup currDQG = this.queryGroupColl.getGroupByUUID(UUID.fromString(queryGenTable.getCell(currentReq, Utility.COL_NAME_UUID)));
													
							// get the JSON config from the generator return statement
							JSONObject configJSON = null;
							JSONParser jParser = new JSONParser();
							
							configJSON = (JSONObject) jParser.parse(queryGenTable.getCell(currentReq, Utility.COL_NAME_CONFIGJSON));
	
							String currentJobId = this.getJobId() + "_edc_" + String.valueOf(currentReq);
							LocalLogger.logToStdErr("config for current qry:");
							LocalLogger.logToStdErr(configJSON.toString() + " jobId: " + currentJobId);
							
							QueryExecuteClient clnt = this.dispatchServiceMgr.getExecuteClient(configJSON, currentJobId );
							ResultsClient rClient = new ResultsClient(this.resConfig);
							queryExecClientName = clnt.getClass().getSimpleName();
							JobTracker tracker = new JobTracker(this.jobTrackerSei);
							String query = queryGenTable.getColumn(Utility.COL_NAME_QUERY)[currentReq];
							
							// TESTING ONLY
							// clnt.getConfig().setServiceServer("localhost");
							
							spunup[currentReq] = new DispatcherWorkThread(currDQG, query, clnt, tracker, rClient, threadExceptionArr, currentReq);
							LocalLogger.logToStdErr("Starting EDC thread " + currentReq + " of a total of " + numQueries);
							spunup[currentReq].start();
						} catch (Exception EEE) {
							throw new Exception("spin up of query thread failed. reported:" + EEE.getMessage());
						}
						// update the counters
						running += 1;
						currentReq += 1;	
					}	
				}
			
			}
			// make sure it all really closed.
			for(int joined = 0; joined < running; joined += 1){
				try {
					spunup[joined + (iteration * threadLimit)].join();
					this.jobTracker.incrementPercentComplete(this.jobID, Math.round(increment), highestStatPercent);
					continue;
				} catch (Exception e) {
					LocalLogger.printStackTrace(e);
					throw new Exception("(Join failure in dispatch threading : joined value was "+ joined + ":: " + e.getClass().toString() + " : "+ e.getMessage() + ")");
				}
			}
		}
		catch(Exception e){
			throw new Exception("Dispatcher threading failure: " + e.getMessage());
		}
		
		// determine if any threads failed 
		for(Exception e : threadExceptionArr){
			if(e == null) { 
				numberThreadsCompletedSuccessfully += 1; 
			} else {
				// at least one thread failed - throw a new Exception based on the first failure
				String message = e.getMessage();
				int MAX_MESSAGE_LENGTH = 500;  	// this message will get displayed in a dialog, so limit its length
				if(message != null && message.length() > MAX_MESSAGE_LENGTH){  
					message = message.substring(0, MAX_MESSAGE_LENGTH) + "...";
				}
				throw new Exception(queryExecClientName + " could not retrieve data: " + message, e);
			}
		}
		
		return numberThreadsCompletedSuccessfully;
	}
	
	/**
	 * placeholder for eventual real getting of column types
	 * @param columnNames
	 * @return
	 */
	private String[] getColumnTypes(String[] columnNames){
		String[] retval = new String[columnNames.length];
		
		// for now, return all strings to move forward in functionality testing
		for(int i = 0; i < columnNames.length; i++){
			// get the name and the type.
			retval[i] = this.queryGroupColl.getColumnType(columnNames[i]);
		}
		return retval;
	}
	/**
	 * Find all unique sets of semantic column data, and assign a UUID to each.
	 * Associate with each UUID all unique sets of edc column data.
	 * 
	 * perform all the binning and sorting required to meaningfully invoke an EDC query generator...
	 * store the types related to the binning values (from the pure semantics part of the query)
	 * @return
	 * @throws Exception
	 */
	private Table performEdcPrepBinning(NodeGroup modifiedForEdc) throws Exception{
		Table retval = null;
		
		// create a query client
		SparqlEndpointInterface nodegroupSei = this.dispatchServiceMgr.getNodegroupSei();
		
		// execute sparql query to get the interim results. 
		String sparql = modifiedForEdc.generateSparql(AutoGeneratedQueryTypes.QUERY_DISTINCT, false, null, null, false);
		TableResultSet moddedResult = (TableResultSet) nodegroupSei.executeQueryAndBuildResultSet(sparql, SparqlResultTypes.TABLE);
		
		// bin the results. 
		String[] edcColNames = this.dispatchServiceMgr.getAddedSparqlIds();  // get the columns added.
		
		
		Table tableFromSparql = moddedResult.getTable();
		
		// create a list of the column names that do not appear in the edcFields array
		ArrayList<String> semanticColNames = new ArrayList<String>();
		
		for(String colname : tableFromSparql.getColumnNames()){
			Boolean found = false;
			// check if current name appears in the edc names
			for(String edcCol : edcColNames){
				if(edcCol.equals(colname)){ 
					found = true;
					break;
				}
			}
			// the value was not in the EDC list. it can be used for binning. 
			if(!found){ semanticColNames.add(colname); }
		}
		
		// perform the actual binning. 
		queryGroupColl = new QueryGroupCollection(tableFromSparql, edcColNames, semanticColNames);
		
		// store values on the semantic column types.
		for(String colName : semanticColNames){
			this.queryGroupColl.setSemanticColumnType(colName, tableFromSparql.getColumnType(colName));
		}
				
		
		retval = queryGroupColl.returnDispatchQueryGroupTable();
		
		// send back the result with proper binning. 
		return retval;
	}

	/**
	 * get the collection of end points which will be used for various EDC actions. 
	 * @return
	 */
	private ArrayList<ServiceEndpointInfo> getEdcEndpoints(){
		ArrayList<ServiceEndpointInfo> retval = new ArrayList<ServiceEndpointInfo>();
		// not yet implemented. 
		return retval;
	}
	
	/**
	 * pass through to the QueryGroupCollection to get the results.
	 * @param columnNamesInOrder
	 * @param columnTypesInnameOrder
	 * @return
	 * @throws Exception 
	 */
	private Table fuseResults(String[] columnNamesInOrder, String[] columnTypesInNameOrder) throws Exception{
		Table retval = this.queryGroupColl.returnFusedResults_parallel(columnNamesInOrder, columnTypesInNameOrder);
		//Table retval = this.qgc.returnFusedResults_parallel(columnNamesInOrder, columnTypesInNameOrder);
		return retval;
	}

	@Override
	public String getConstraintType() throws Exception {
		return this.dispatchServiceMgr.getConstraintType();
	}

	@Override
	public String[] getConstraintVariableNames() throws Exception {
		return this.dispatchServiceMgr.getConstraintVariableNames();
	}

	/**
	 * Converts hash object returned by a QueryGenerator  	(HashMap<UUID, Object> queriesHash)  
	 * into a table with columns needed by EDCDispatcher    (Utility.COL_NAME_UUID, Utility.COL_NAME_QUERY, Utility.COL_NAME_CONFIGJSON)
	 */
	public static Table getTableForDispatcher(HashMap<UUID, Object> queriesHash) throws Exception{
		Table retTable = new Table(EDC_DISPATCHER_COL_NAMES, EDC_DISPATCHER_COL_TYPES, null);
		for(UUID uuid : queriesHash.keySet().toArray(new UUID[queriesHash.keySet().size()])){
			QueryList queryListObj = (QueryList) queriesHash.get(uuid);
			ArrayList<Query> queryList = queryListObj.getQueries();
			for(Query query : queryList){
				ArrayList<String> row = new ArrayList<String>();
				row.add(uuid.toString());
				row.add(query.getQuery());
				row.add(queryListObj.getConfig().toString());  // add the config info for this query.
				retTable.addRow(row);
			}
		}
		return retTable;
	}
	
}
