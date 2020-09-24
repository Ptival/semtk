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

package com.ge.research.semtk.api.nodeGroupExecution.client;

import java.net.ConnectException;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import com.ge.research.semtk.api.nodeGroupExecution.NodeGroupExecutor;
import com.ge.research.semtk.auth.ThreadAuthenticator;
import com.ge.research.semtk.belmont.NodeGroup;
import com.ge.research.semtk.belmont.runtimeConstraints.RuntimeConstraintManager;
import com.ge.research.semtk.load.client.SharedIngestNgeClient;
import com.ge.research.semtk.load.utility.SparqlGraphJson;
import com.ge.research.semtk.resultSet.RecordProcessResults;
import com.ge.research.semtk.resultSet.SimpleResultSet;
import com.ge.research.semtk.resultSet.Table;
import com.ge.research.semtk.resultSet.TableResultSet;
import com.ge.research.semtk.services.client.RestClientConfig;
import com.ge.research.semtk.sparqlX.SparqlConnection;
import com.ge.research.semtk.sparqlX.dispatch.QueryFlags;
import com.ge.research.semtk.utility.LocalLogger;
import com.ge.research.semtk.utility.Utility;

/** 
 * Most of the operations wrapped by this client are asynchronous.  Many functions will ping and wait for results.
 * 
 * There are many shared function parameters:
 * 	
 *  nodegroupID -- string ID for the nodegroup to be executed. this assumes that the node group resides in a nodegroup store
 *  overrideConn -- the sparql connection rendered to JSON. please see com.ge.research.semtk.sparqlX.SparqlConnection for details.
 *  edcConstraintsJson -- the EDC Constraints rendered as JSON. expected format {\"@constraintSet\":{\"@op\":\"AND\",\"@constraints\":[]}} . these will be better documented in the future.
 *  runtimeConstraints -- the runtime constraints rendered as JSON. this is an array of JSON objects of the format 
 * 									{"SparqlID" : "<value>", "Operator" : "<operator>", "Operands" : [<operands>] }
 * 									for more details, please the package com.ge.research.semtk.belmont.runtimeConstraints .
 *  targetObjectSparqlId -- the ID of the object to filter for valid values of. these are the sparql IDs used in the nodegroup.
 *  sparqlGraphJson - a SparqlGraphJson object which contains a nodegroup and connection
 * 
 * @author 200001934
 *
 */
public class NodeGroupExecutionClient extends SharedIngestNgeClient {
	
	// json keys
	// TODO should probably move these elsewhere and/or consolidate with other classes
	private static final String JSON_KEY_ID = "id";
	private static final String JSON_KEY_JOB_ID = "jobID";
	private static final String JSON_KEY_NODEGROUP_ID = "nodeGroupId";
	private static final String JSON_KEY_LIMIT_OVERRIDE = "limitOverride";
	private static final String JSON_KEY_OFFSET_OVERRIDE = "offsetOverride";
	private static final String JSON_KEY_MAX_WAIT_MSEC = "maxWaitMsec";
	private static final String JSON_KEY_NODEGROUP  = "jsonRenderedNodeGroup";
	private static final String JSON_KEY_PERCENT_COMPLETE  = "percentComplete";
	private static final String JSON_KEY_SPARQL_CONNECTION = "sparqlConnection";
	private static final String JSON_KEY_SPARQL = "sparql";
	private static final String JSON_KEY_RUNTIME_CONSTRAINTS = "runtimeConstraints";
	private static final String JSON_KEY_EDC_CONSTRAINTS = "externalDataConnectionConstraints";
	private static final String JSON_KEY_FLAGS = "flags";
	
	// service mapping
	private static final String mappingPrefix = "/nodeGroupExecution";
	
	// endpoints
	private static final String jobStatusEndpoint = "/jobStatus";
	private static final String jobStatusMessageEndpoint = "/jobStatusMessage";
	private static final String jobCompletionCheckEndpoint = "/getJobCompletionCheck";
	private static final String jobCompletionPercentEndpoint = "/getJobCompletionPercentage";
	private static final String waitForPercentOrMsecEndpoint = "/waitForPercentOrMsec";

	private static final String resultsLocationEndpoint = "/getResultsLocation";
	private static final String dispatchByIdEndpoint = "/dispatchById";
	private static final String dispatchFromNodegroupEndpoint = "/dispatchFromNodegroup";
	private static final String ingestFromCsvStringsNewConnectionEndpoint = "/ingestFromCsvStringsNewConnection";
	private static final String ingestFromCsvStringsByIdEndpoint = "/ingestFromCsvStringsById";
	private static final String ingestFromCsvStringsByIdAsyncEndpoint = "/ingestFromCsvStringsByIdAsync";
	private static final String ingestFromCsvStringsAndTemplateNewConnectionEndpoint = "/ingestFromCsvStringsAndTemplateNewConnection";
	private static final String ingestFromCsvStringsAndTemplateAsync = "/ingestFromCsvStringsAndTemplateAsync";
	private static final String getResultsTableEndpoint = "/getResultsTable";
	private static final String getResultsJsonLdEndpoint = "/getResultsJsonLd";
	private static final String getResultsJsonBlobEndpoint = "/getResultsJsonBlob";
	private static final String getRuntimeConstraintsByNodeGroupID = "/getRuntimeConstraintsByNodeGroupID";
	private static final String getIngestionColumnsById = "/getIngestionColumnsById";
	private static final String dispatchSelectByIdEndpoint = "/dispatchSelectById";
	private static final String dispatchSelectByIdSyncEndpoint = "/dispatchSelectByIdSync";
	private static final String dispatchSelectFromNodegroupEndpoint = "/dispatchSelectFromNodegroup";
	private static final String dispatchCountByIdEndpoint = "/dispatchCountById";
	private static final String dispatchCountFromNodegroupEndpoint = "/dispatchCountFromNodegroup";
	private static final String dispatchFilterByIdEndpoint = "/dispatchFilterById";
	private static final String dispatchFilterFromNodegroupEndpoint ="/dispatchFilterFromNodegroup";
	private static final String dispatchDeleteByIdEndpoint = "/dispatchDeleteById";
	private static final String dispatchDeleteFromNodegroupEndpoint = "/dispatchDeleteFromNodegroup";
	private static final String dispatchRawSparqlEndpoint = "/dispatchRawSparql";
	private static final String dispatchConstructByIdEndpoint = "/dispatchConstructById";
	private static final String dispatchConstructFromNodegroupEndpoint = "/dispatchConstructFromNodegroup";
	private static final String dispatchConstructByIdEndpointForInstanceManipulationEndpoint = "/dispatchConstructForInstanceManipulationById";
	private static final String dispatchConstructFromNodegroupEndpointForInstanceManipulationEndpoint = "/dispatchConstructForInstanceManipulationFromNodegroup";

	/**
	 * Constructor
	 */
	public NodeGroupExecutionClient() {
		super("nodeGroupExecution/");
	}
	
	public NodeGroupExecutionClient(RestClientConfig conf, String mappingPrefix){
		super(conf, "nodeGroupExecution/");
	}
	
	/**
	 * preferred constructor
	 * @param necc
	 */
	public NodeGroupExecutionClient (NodeGroupExecutionClientConfig necc){
		super(necc, "nodeGroupExecution/");
	}
	
	@Override
	public void buildParametersJSON() throws Exception {
	}

	@Override
	public void handleEmptyResponse() throws Exception {
	}
	
	
	
	public String getServiceUser() {
		return ((NodeGroupExecutionClientConfig)this.conf).getServiceUser();
	}
	
	public String getServicePassword() {
		return ((NodeGroupExecutionClientConfig)this.conf).getServicePassword();
	}
	
	public String getJobStatus(String jobId) throws Exception{
		SimpleResultSet ret = this.execGetJobStatus(jobId);
		return ret.getResult("status");
	}
	
	public boolean getJobSuccess(String jobId) throws Exception {
		return this.getJobStatus(jobId).equalsIgnoreCase("success");
	}
	
	/**
	 * Raw call to get job status
	 * @param jobId
	 * @return SimpleResultSet containing "message"
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public SimpleResultSet execGetJobStatus(String jobId) throws Exception{
		SimpleResultSet retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + jobStatusEndpoint);
		this.parametersJSON.put(JSON_KEY_JOB_ID, jobId);
		
		try{
			retval = SimpleResultSet.fromJson((JSONObject) this.execute() );
			retval.throwExceptionIfUnsuccessful();
		}
		finally{
			this.reset();
		}
		
		return retval;
	}
	
	/**
	 * Check if job is complete
	 * @param jobId
	 * @return boolean
	 * @throws Exception
	 */
	public Boolean isJobComplete(String jobId) throws Exception{
		SimpleResultSet ret = this.execGetJobCompletionCheck(jobId);
		
		String val = ret.getResult("completed");
		if(val.equalsIgnoreCase("true")) { return true; }
		else{ return false; }
	}
	
	/**
	 * Raw call for job completion
	 * @param jobId
	 * @return SimpleResultSet with "completed"
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public SimpleResultSet execGetJobCompletionCheck(String jobId) throws Exception{
		SimpleResultSet retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + jobCompletionCheckEndpoint);
		this.parametersJSON.put(JSON_KEY_JOB_ID, jobId);
		
		try{
			retval = SimpleResultSet.fromJson((JSONObject) this.execute() );
			retval.throwExceptionIfUnsuccessful();
		} finally{
			this.reset();
		}		
		return retval;		
	}
	
	/**
	 * Get job status message
	 * @param jobId
	 * @return String
	 * @throws Exception
	 */
	public String getJobStatusMessage(String jobId) throws Exception{
		SimpleResultSet ret = execGetJobStatusMessage(jobId);
		return ret.getResult("message");
	}
	
	/**
	 * Raw call to get job status message
	 * @param jobId
	 * @return SimpleResultSet containing "message"
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public SimpleResultSet execGetJobStatusMessage(String jobId) throws Exception{
		SimpleResultSet retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + jobStatusMessageEndpoint);
		this.parametersJSON.put(JSON_KEY_JOB_ID, jobId);
		
		try{
			retval = SimpleResultSet.fromJson((JSONObject) this.execute() );
			retval.throwExceptionIfUnsuccessful();
		} finally{
			this.reset();
		}
		return retval;		
	}
	
	/**
	 * Raw call to get job percent complete
	 * @param jobId
	 * @return SimpleResultSet
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public SimpleResultSet execGetJobCompletionPercentage(String jobId) throws Exception {
		SimpleResultSet retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + jobCompletionPercentEndpoint);
		this.parametersJSON.put(JSON_KEY_JOB_ID, jobId);
		
		try{
			retval = SimpleResultSet.fromJson((JSONObject) this.execute() );
			retval.throwExceptionIfUnsuccessful();
		}finally{
			this.reset();
		}
		return retval;
	}
	
	/**
	 * Get results table throwing exceptions if anything goes wrong.
	 * Most common uses are a select query success or an ingestion failure.
	 * @param jobId
	 * @return Table
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	
	public Table getResultsTable(String jobId) throws Exception {
		TableResultSet retval = new TableResultSet();
		
		conf.setServiceEndpoint(mappingPrefix + getResultsTableEndpoint);
		this.parametersJSON.put(JSON_KEY_JOB_ID, jobId);
		
		try{
			retval = this.executeWithTableResultReturn();
		} finally{
			this.reset();
		}
		
		if (! retval.getSuccess()) {
			throw new Exception(String.format("Job failed.  JobId='%s' Message='%s'", jobId, retval.getRationaleAsString("\n")));
		}
		
		return retval.getTable();
	}
	
	/**
	 * Get JSON-LD results
	 * Most common use is Construct query
	 * @param jobId
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public JSONObject execGetResultsJsonLd(String jobId) throws Exception {
		JSONObject retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + getResultsJsonLdEndpoint);
		this.parametersJSON.put(JSON_KEY_JOB_ID, jobId);
		
		try{
			retval = (JSONObject) this.execute();
		} finally{
			this.reset();
		}
		
		return retval;
	}
	
	
	/**
	 * get results URLs",
	 * DEPRECATED: URLS may not work in secure deployment of SemTK
	 * Results service /getTableResultsJsonForWebClient and /getTableResultsCsvForWebClient are safer
	 * @param jobId
	 * @return
	 * @throws Exception
	 */
	public Table getResultsLocation(String jobId) throws Exception{
		TableResultSet ret = this.execGetResultsLocation(jobId);
		return ret.getTable();
	}
	
	/**
	 * get results URLs",
	 * DEPRECATED: URLS may not work in secure deployment of SemTK
	 * Results service /getTableResultsJsonForWebClient and /getTableResultsCsvForWebClient are safer
	 * @param jobId
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public TableResultSet execGetResultsLocation(String jobId) throws Exception{
		TableResultSet retval = new TableResultSet();
		
		conf.setServiceEndpoint(mappingPrefix + resultsLocationEndpoint);
		this.parametersJSON.put(JSON_KEY_JOB_ID, jobId);
		
		try{
			JSONObject jobj = (JSONObject) this.execute();
			retval.readJson(jobj);
			retval.throwExceptionIfUnsuccessful();
		}
		finally{
			this.reset();
		}
		
		return retval;
	}

	/**
	 * Raw call to wait for first of a maxWaitMsec or percentComplete
	 * @param jobId
	 * @param maxWaitMsec
	 * @param percentComplete
	 * @return SimpleResultSet containing "percentComplete"
	 * @throws Exception
	 */
	public SimpleResultSet execWaitForPercentOrMsec(String jobId, int maxWaitMsec, int percentComplete) throws Exception {
		SimpleResultSet retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + waitForPercentOrMsecEndpoint);
		this.parametersJSON.put(JSON_KEY_JOB_ID, jobId);
		this.parametersJSON.put(JSON_KEY_MAX_WAIT_MSEC, maxWaitMsec);
		this.parametersJSON.put(JSON_KEY_PERCENT_COMPLETE, percentComplete);
		try {
			retval = SimpleResultSet.fromJson((JSONObject) this.execute() );
			retval.throwExceptionIfUnsuccessful();
		} finally{
			this.reset();
		}
		return retval;
	}
	
	/**
	 * Wait for first of maxWaitMsec or percentComplete
	 * @param jobId
	 * @param maxWaitMsec
	 * @param percentComplete
	 * @return int current job percentComplete
	 * @throws Exception
	 */
	public int waitForPercentOrMsec(String jobId, int maxWaitMsec, int percentComplete) throws Exception {
		SimpleResultSet ret = this.execWaitForPercentOrMsec(jobId, maxWaitMsec, percentComplete);
		return ret.getResultInt("percentComplete");
	}
	
	
	
	
	/**
	 * Raw call to launch a Select query 
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @return
	 * @throws Exception
	 */
	public String dispatchSelectByIdToJobId(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson) throws Exception{
		return this.dispatchSelectByIdToJobId(nodegroupID, overrideConn, edcConstraintsJson, runtimeConstraintsJson, -1, -1, null);
	}
	
	/**
	 * Run a Select query by nodegroupID
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @param limitOverride
	 * @param offsetOverride
	 * @param flags
	 * @return String jobId
	 * @throws Exception
	 */
	public String dispatchSelectByIdToJobId(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson, int limitOverride, int offsetOverride, QueryFlags flags) throws Exception{
		SimpleResultSet ret =  this.execDispatchSelectById(nodegroupID, overrideConn, edcConstraintsJson, runtimeConstraintsJson, limitOverride, offsetOverride, flags);
		return ret.getResult("JobId");
	}

	/**
	 * Run a construct query by nodegroupID
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @return
	 * @throws Exception
	 */
	public String dispatchConstructByIdToJobId(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson) throws Exception{
		SimpleResultSet ret =  this.execDispatchConstructById(nodegroupID, overrideConn, edcConstraintsJson, runtimeConstraintsJson);
		return ret.getResult("JobId");
	}

	
	
	
	/**
	 * Run select query by nodegroupID
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @return Table of results
	 * @throws Exception
	 */
	public Table execDispatchSelectByIdToTable(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson) throws Exception {
		return this.execDispatchSelectByIdToTable(nodegroupID, overrideConn, edcConstraintsJson, runtimeConstraintsJson, -1, -1, null);
	}

	/**
	 * Run select query by nodegroupID
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @param limitOverride
	 * @param offsetOverride
	 * @param flags
	 * @return Table of results
	 * @throws Exception
	 */
	public Table execDispatchSelectByIdToTable(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson, int limitOverride, int offsetOverride, QueryFlags flags) throws Exception {
		
		// dispatch the job
		String jobId = this.dispatchSelectByIdToJobId(nodegroupID, overrideConn, edcConstraintsJson, runtimeConstraintsJson, limitOverride, offsetOverride, flags);
		
		try {
			return this.waitForJobAndGetTable(jobId);
		} catch (Exception e) {
			// Add nodegroupID and "SELECT" to the error message
			throw new Exception(String.format("Error executing SELECT on nodegroup id='%s'", nodegroupID), e);
		}
	}
	
	/**
	 * Run a query to find all instance values for a target object given a Select query
	 * @param nodegroupID
	 * @param targetObjectSparqlId - object to be checked
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @return Table of results
	 * @throws Exception
	 */
	public Table execDispatchFilterByIdToTable(String nodegroupID, String targetObjectSparqlId, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson) throws Exception {
		return this.execDispatchFilterByIdToTable(nodegroupID, targetObjectSparqlId, overrideConn, edcConstraintsJson, runtimeConstraintsJson, -1, -1, null);
	}

	/**
	 * Run a query to find all instance values for a target object given a Select query
	 * @param nodegroupID
	 * @param targetObjectSparqlId
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @param limitOverride
	 * @param offsetOverride
	 * @param flags
	 * @return Table of results
	 * @throws Exception
	 */
	public Table execDispatchFilterByIdToTable(String nodegroupID, String targetObjectSparqlId, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson, int limitOverride, int offsetOverride, QueryFlags flags) throws Exception {
		
		// dispatch the job
		String jobId = this.dispatchFilterByIdToJobId(nodegroupID, targetObjectSparqlId, overrideConn, edcConstraintsJson, runtimeConstraintsJson, limitOverride, offsetOverride);
		
		try {
			return this.waitForJobAndGetTable(jobId);
		} catch (Exception e) {
			// Add nodegroupID and "SELECT" to the error message
			throw new Exception(String.format("Error executing SELECT on nodegroup id='%s'", nodegroupID), e);
		}
	}
	
	/**
	 * Run a construct query to JSON-LD
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @return JSON-LD results
	 * @throws Exception
	 */
	public JSONObject execDispatchConstructByIdToJsonLd(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson) throws Exception {
		
		// dispatch the job
		String jobId = this.dispatchConstructByIdToJobId(nodegroupID, overrideConn, edcConstraintsJson, runtimeConstraintsJson);
		
		try {
			return this.waitForJobAndGetJsonLd(jobId);			
		} catch (Exception e) {
			// Add nodegroupID and "SELECT" to the error message
			throw new Exception(String.format("Error executing Construct on nodegroup id='%s'", nodegroupID), e);
		}		
	}

	
	/**
	 * Preferred way to wait for a job to complete
	 * @param jobId
	 * @param freqMsec - a ping freq such as 10,000.  Will return sooner if job finishes
	 * @param maxTries - throw exception after this many tries
	 * @throws Exception
	 */
	private void waitForCompletion(String jobId, int freqMsec, int maxTries ) throws Exception {
		int percent = 0;
		
		for (int i=0; i < maxTries; i++) {
			percent = this.waitForPercentOrMsec(jobId, freqMsec, 100);
			if (percent == 100) {
				return;
			}
		}
		throw new Exception("Job " + jobId + " is only " + String.valueOf(percent) + "% complete after " + String.valueOf(maxTries) + " tries.");
	}
	
	/**
	 * Wait forever for a job to complete, pinging every 9 seconds
	 * @param jobId
	 * @throws Exception
	 */
	public void waitForCompletion(String jobId) throws Exception {
		this.waitForCompletion(jobId, 9000, Integer.MAX_VALUE);
	}
	
	/**
	 * Given jobId, check til job is done, check for success, get table
	 * @param jobId
	 * @return
	 * @throws Exception - if anything other than a valid table is returned
	 */
	public Table waitForJobAndGetTable(String jobId) throws Exception {
		// wait for completion
		this.waitForCompletion(jobId);
		
		// check for success
		if (this.getJobSuccess(jobId)) {
			return this.getResultsTable(jobId);
		} else {
			String msg = this.getJobStatusMessage(jobId);
			throw new Exception(String.format("Job %s failed with message='%s'", jobId, msg));
		}
	}
	
	/**
	 * Wait for (an ingestion) job that gives a message on success and table string on error
	 * @param jobId
	 * @return
	 * @throws Exception
	 */
	public String waitForIngestionJob(String jobId) throws Exception {
		// wait for completion
		this.waitForCompletion(jobId);
		
		// check for success
		if (this.getJobSuccess(jobId)) {
			return this.getJobStatusMessage(jobId);
		} else {
			String msg = this.getResultsTable(jobId).toCSVString();
			throw new Exception(String.format("Job %s failed with error table:\n%s", jobId, msg));
		}
	}

	/**
	 * For use with construct, wait for job and get the results
	 * @param jobId
	 * @return JSON-LD results
	 * @throws Exception
	 */
	public JSONObject waitForJobAndGetJsonLd(String jobId) throws Exception {
		
		waitForCompletion(jobId);
		
		// check for success
		if (this.getJobSuccess(jobId)) {
			return this.execGetResultsJsonLd(jobId);
		} else {
			String msg = this.getJobStatusMessage(jobId);
			throw new Exception(String.format("Job %s failed with message='%s'", jobId, msg));
		}
	}
	

	/**
	 * Raw call to launch a select query by nodegroupID
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @param flags
	 * @return SimpleResultSet with "JobId"
	 * @throws Exception
	 */
	public SimpleResultSet execDispatchSelectById(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson, QueryFlags flags) throws Exception{
		return this.execDispatchSelectById(nodegroupID, overrideConn, edcConstraintsJson, runtimeConstraintsJson, -1, -1, flags) ;
	}
	
	/**
	 * Raw call to launch a select query by nodegroupID
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @param limitOverride
	 * @param offsetOverride
	 * @param flags
	 * @return SimpleResultSet with "JobId"
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public SimpleResultSet execDispatchSelectById(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson, int limitOverride, int offsetOverride, QueryFlags flags) throws Exception{
		SimpleResultSet retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + dispatchSelectByIdEndpoint);
		this.parametersJSON.put(JSON_KEY_NODEGROUP_ID, nodegroupID);
		this.parametersJSON.put(JSON_KEY_LIMIT_OVERRIDE, limitOverride);
		this.parametersJSON.put(JSON_KEY_OFFSET_OVERRIDE, offsetOverride);

		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, overrideConn.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_EDC_CONSTRAINTS, edcConstraintsJson == null ? null : edcConstraintsJson.toJSONString());	
		this.parametersJSON.put(JSON_KEY_RUNTIME_CONSTRAINTS, runtimeConstraintsJson == null ? null : runtimeConstraintsJson.toJSONString());
		this.parametersJSON.put(JSON_KEY_FLAGS, flags == null ? null : flags.toJSONString());		

		
		try{
			LocalLogger.logToStdErr("sending executeDispatchSelectById request");
			retval = SimpleResultSet.fromJson((JSONObject) this.execute() );
			retval.throwExceptionIfUnsuccessful(String.format("Error running SELECT on nodegroup id='%s'", nodegroupID));
		}
		finally{
			this.reset();
		}
		LocalLogger.logToStdErr("executeDispatchSelectById request finished without exception");
		return retval;
	}
	
	/**
	 * Run synchronous Select.   Warning HTTP protocols could break the connection before it completes.
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @param flags
	 * @return TableResultSet of results
	 * @throws Exception
	 */
	public TableResultSet execDispatchSelectByIdSync(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson, QueryFlags flags) throws Exception{
		return this.execDispatchSelectByIdSync(nodegroupID, overrideConn, edcConstraintsJson, runtimeConstraintsJson, -1, -1, null);
	}
	
	/**
	 * Run synchronous Select.   Warning HTTP protocols could break the connection before it completes.
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @param limitOverride
	 * @param offsetOverride
	 * @param flags
	 * @return TableResultSet of results
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public TableResultSet execDispatchSelectByIdSync(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson, int limitOverride, int offsetOverride, QueryFlags flags) throws Exception{
		TableResultSet retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + dispatchSelectByIdSyncEndpoint);
		this.parametersJSON.put(JSON_KEY_NODEGROUP_ID, nodegroupID);
		this.parametersJSON.put(JSON_KEY_LIMIT_OVERRIDE, limitOverride);
		this.parametersJSON.put(JSON_KEY_OFFSET_OVERRIDE, offsetOverride);

		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, overrideConn.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_EDC_CONSTRAINTS, edcConstraintsJson == null ? null : edcConstraintsJson.toJSONString());	
		this.parametersJSON.put(JSON_KEY_RUNTIME_CONSTRAINTS, runtimeConstraintsJson == null ? null : runtimeConstraintsJson.toJSONString());	
		this.parametersJSON.put(JSON_KEY_FLAGS, flags == null ? null : flags.toJSONString());		

		
		try{
			LocalLogger.logToStdErr("sending executeDispatchSelectByIdSync request");
			retval = new TableResultSet((JSONObject) this.execute());
			retval.throwExceptionIfUnsuccessful(String.format("Error running SELECT on nodegroup id='%s'", nodegroupID));
		}
		finally{
			this.reset();
		}
		LocalLogger.logToStdErr("executeDispatchSelectByIdSync request finished without exception");
		return retval;
	}
	
	/**
	 * raw call to launch construct query by nodegroupID
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @return SimpleResultSet with "JobId"
	 * @throws Exception
	 */
	public SimpleResultSet execDispatchConstructById(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson) throws Exception{
		return this.execDispatchById(nodegroupID, overrideConn, edcConstraintsJson, null, runtimeConstraintsJson, -1, -1);
	}
		
	/**
	 * raw call to launch construct query by nodegroupID
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @param limitOverride
	 * @param offsetOverride
	 * @return SimpleResultSet with "JobId"
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public SimpleResultSet execDispatchConstructById(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson, int limitOverride, int offsetOverride) throws Exception{
		SimpleResultSet retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + dispatchConstructByIdEndpoint);
		this.parametersJSON.put(JSON_KEY_NODEGROUP_ID, nodegroupID);
		this.parametersJSON.put(JSON_KEY_LIMIT_OVERRIDE, limitOverride);
		this.parametersJSON.put(JSON_KEY_OFFSET_OVERRIDE, offsetOverride);

		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, overrideConn.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_EDC_CONSTRAINTS, edcConstraintsJson == null ? null : edcConstraintsJson.toJSONString());	
		this.parametersJSON.put(JSON_KEY_RUNTIME_CONSTRAINTS,            runtimeConstraintsJson == null ? null : runtimeConstraintsJson.toJSONString());		
		
		try{
			LocalLogger.logToStdErr("sending executeDispatchSelectById request");
			retval = SimpleResultSet.fromJson((JSONObject) this.execute() );
			retval.throwExceptionIfUnsuccessful(String.format("Error running SELECT on nodegroup id='%s'", nodegroupID));
		}
		finally{
			this.reset();
		}
		LocalLogger.logToStdErr("executeDispatchSelectById request finished without exception");
		return retval;
	}
	
	/**
	 * Run raw sparql
	 * @param sparql
	 * @param conn
	 * @return SimpleResultSet with "JobId"
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public SimpleResultSet execDispatchRawSparql(String sparql, SparqlConnection conn) throws Exception{
		SimpleResultSet retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + dispatchRawSparqlEndpoint);
		this.parametersJSON.put(JSON_KEY_SPARQL, sparql);
		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, conn.toJson().toJSONString());
	
		
		try{
			LocalLogger.logToStdErr("sending raw sparql request");
			retval = SimpleResultSet.fromJson((JSONObject) this.execute());
			retval.throwExceptionIfUnsuccessful(String.format("Error running raw sparql"));
		}
		finally{
			this.reset();
		}
		LocalLogger.logToStdErr("executeDispatchRawSparql request finished without exception");
		return retval;
	}
	
	/**
	 * Run raw SPARQL and wait for results
	 * @param sparql
	 * @param conn
	 * @return Table of results
	 * @throws Exception
	 */
	public Table dispatchRawSparql(String sparql, SparqlConnection conn) throws Exception{
		SimpleResultSet ret = this.execDispatchRawSparql(sparql, conn);
		Table tab = this.waitForJobAndGetTable(ret.getResult("JobId"));
		return tab;		
	}
	
	
	/**
	 * Launch count query 
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @return String jobId
	 * @throws Exception
	 */
	public String dispatchCountByIdToJobId(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson) throws Exception{
		SimpleResultSet ret =  this.execDispatchCountById(nodegroupID, overrideConn, edcConstraintsJson, runtimeConstraintsJson);
		return ret.getResult("JobId");
	}
	
	/**
	 * Launch count query 
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @param limitOverride
	 * @param offsetOverride
	 * @return String jobId
	 * @throws Exception
	 */
	public String dispatchCountByIdToJobId(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson, int limitOverride, int offsetOverride) throws Exception{
		SimpleResultSet ret =  this.execDispatchCountById(nodegroupID, overrideConn, edcConstraintsJson, runtimeConstraintsJson, limitOverride, offsetOverride);
		return ret.getResult("JobId");
	}
	
	/**
	 * Raw call to launch a count query
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @return SimpleResultSet containing "JobId"
	 * @throws Exception
	 */
	public SimpleResultSet execDispatchCountById(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson) throws Exception{
		return this.execDispatchCountById(nodegroupID, overrideConn, edcConstraintsJson, runtimeConstraintsJson, -1, -1);
	}

	/**
	 * Raw call to launch a count query
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @param limitOverride
	 * @param offsetOverride
	 * @return SimpleResultSet containing "JobId"
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public SimpleResultSet execDispatchCountById(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson, int limitOverride, int offsetOverride) throws Exception{
		SimpleResultSet retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + dispatchCountByIdEndpoint);
		this.parametersJSON.put(JSON_KEY_NODEGROUP_ID, nodegroupID);
		this.parametersJSON.put(JSON_KEY_LIMIT_OVERRIDE, limitOverride);
		this.parametersJSON.put(JSON_KEY_OFFSET_OVERRIDE, offsetOverride);

		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, overrideConn.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_EDC_CONSTRAINTS, edcConstraintsJson == null ? null : edcConstraintsJson.toJSONString());	
		this.parametersJSON.put(JSON_KEY_RUNTIME_CONSTRAINTS,            runtimeConstraintsJson == null ? null : runtimeConstraintsJson.toJSONString());		
		
		
		try{
			LocalLogger.logToStdErr("sending executeDispatchCountById request");
			retval = SimpleResultSet.fromJson((JSONObject) this.execute() );
			retval.throwExceptionIfUnsuccessful();
		}
		finally{
			this.reset();
		}
		LocalLogger.logToStdErr("executeDispatchCountById request finished without exception");
		return retval;
	}
	
	/**
	 * Run a count query by nodegroupID and wait for result
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraints
	 * @return Long count results
	 * @throws Exception
	 */
	public Long dispatchCountById(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson) throws Exception{
		SimpleResultSet ret =  this.execDispatchCountById(nodegroupID, overrideConn, edcConstraintsJson, runtimeConstraintsJson);
		
		Table tab = this.waitForJobAndGetTable(ret.getResult("JobId"));
		return tab.getCellAsLong(0, 0);
	}
	
	/**
	 * Run a count query by nodegroup and wait for result
	 * @param nodegroup
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraints
	 * @return
	 * @throws Exception
	 */
	public Long dispatchCountByNodegroup(NodeGroup nodegroup, SparqlConnection overrideConn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints) throws Exception{
		SimpleResultSet ret =  this.execDispatchCountFromNodeGroup(nodegroup, overrideConn, edcConstraintsJson, runtimeConstraints);
		
		Table tab = this.waitForJobAndGetTable(ret.getResult("JobId"));
		return tab.getCellAsLong(0, 0);
	}
	
	
	
	/**
	 * Launch a filter query by nodegroupID
	 * @param nodegroupID
	 * @param targetObjectSparqlId
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @return String jobId
	 * @throws Exception
	 */
	public String dispatchFilterByIdToJobId(String nodegroupID, String targetObjectSparqlId, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson) throws Exception{
		SimpleResultSet ret =  this.execDispatchFilterById(nodegroupID, targetObjectSparqlId, overrideConn, edcConstraintsJson, runtimeConstraintsJson);
		return ret.getResult("JobId");
	}
	/**
	 * Launch a filter query by nodegroupID
	 * @param nodegroupID
	 * @param targetObjectSparqlId
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @param limitOverride
	 * @param offsetOverride
	 * @return String jobId
	 * @throws Exception
	 */
	public String dispatchFilterByIdToJobId(String nodegroupID, String targetObjectSparqlId, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson, int limitOverride, int offsetOverride) throws Exception{
		SimpleResultSet ret =  this.execDispatchFilterById(nodegroupID, targetObjectSparqlId, overrideConn, edcConstraintsJson, runtimeConstraintsJson, limitOverride, offsetOverride);
		return ret.getResult("JobId");
	}

	/**
	 * Raw call to launch a filter query by nodegroupID
	 * @param nodegroupID
	 * @param targetObjectSparqlId
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @return SimpleResultSet containing "JobId"
	 * @throws Exception
	 */
	public SimpleResultSet execDispatchFilterById(String nodegroupID, String targetObjectSparqlId, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson) throws Exception{
		return this.execDispatchFilterById(nodegroupID, targetObjectSparqlId, overrideConn, edcConstraintsJson, runtimeConstraintsJson, -1, -1);
	}

	/**
	 * Raw call to launch a filter query by nodegroupID
	 * @param nodegroupID
	 * @param targetObjectSparqlId - object whose instance values should be returned
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @param limitOverride
	 * @param offsetOverride
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public SimpleResultSet execDispatchFilterById(String nodegroupID, String targetObjectSparqlId, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson, int limitOverride, int offsetOverride) throws Exception{
			SimpleResultSet retval = null;
			
			conf.setServiceEndpoint(mappingPrefix + dispatchFilterByIdEndpoint);
			this.parametersJSON.put(JSON_KEY_NODEGROUP_ID, nodegroupID);
			this.parametersJSON.put(JSON_KEY_LIMIT_OVERRIDE, limitOverride);
			this.parametersJSON.put(JSON_KEY_OFFSET_OVERRIDE, offsetOverride);

			this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, overrideConn.toJson().toJSONString());
			this.parametersJSON.put("targetObjectSparqlId", targetObjectSparqlId);
			this.parametersJSON.put(JSON_KEY_EDC_CONSTRAINTS, edcConstraintsJson == null ? null : edcConstraintsJson.toJSONString());	
			this.parametersJSON.put(JSON_KEY_RUNTIME_CONSTRAINTS,      runtimeConstraintsJson == null ? null : runtimeConstraintsJson.toJSONString());		
			
			
			try{
				LocalLogger.logToStdErr("sending executeDispatchFilterById request");
				retval = SimpleResultSet.fromJson((JSONObject) this.execute() );
				retval.throwExceptionIfUnsuccessful();
			}
			finally{
				this.reset();
			}
			LocalLogger.logToStdErr("executeDispatchFilterById request finished without exception");
			return retval;
		}
	
	/**
	 * Launch a delete query by nodegroupID
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @return String jobId
	 * @throws Exception
	 */
	public String dispatchDeleteByIdToJobId(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson) throws Exception{
		SimpleResultSet ret =  this.execDispatchDeleteById(nodegroupID, overrideConn, edcConstraintsJson, runtimeConstraintsJson);
		return ret.getResult("JobId");
	}
		
	/**
	 * Launch a delete query by nodegroupID
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @return String jobId
	 * @throws Exception
	 */
	public String dispatchDeleteByIdToSuccessMsg(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson) throws Exception{
		String jobId = this.dispatchDeleteByIdToJobId(nodegroupID, overrideConn, edcConstraintsJson, runtimeConstraintsJson);
		this.waitForCompletion(jobId);
		if (this.getJobSuccess(jobId)) {
			return this.getResultsTable(jobId).getCell(0, 0);
		} else {
			throw new Exception(this.getJobStatusMessage(jobId));
		}
	}

	/**
	 * Raw call to launch a delete query by nodegroupID
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @return SimpleResultSet with "JobId"
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public SimpleResultSet execDispatchDeleteById(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson) throws Exception{
			SimpleResultSet retval = null;
			
			conf.setServiceEndpoint(mappingPrefix + dispatchDeleteByIdEndpoint);
			this.parametersJSON.put(JSON_KEY_NODEGROUP_ID, nodegroupID);
			this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, overrideConn.toJson().toJSONString());
			this.parametersJSON.put(JSON_KEY_EDC_CONSTRAINTS, edcConstraintsJson == null ? null : edcConstraintsJson.toJSONString());	
			this.parametersJSON.put(JSON_KEY_RUNTIME_CONSTRAINTS,            runtimeConstraintsJson == null ? null : runtimeConstraintsJson.toJSONString());		
			
			try{
				LocalLogger.logToStdErr("sending executeDispatchDeleteById request");
				retval = SimpleResultSet.fromJson((JSONObject) this.execute() );
				retval.throwExceptionIfUnsuccessful();
			}
			finally{
				this.reset();
			}
			LocalLogger.logToStdErr("executeDispatchDeleteById request finished without exception");
			return retval;
		}

// action-specific endpoints for nodegroup-based executions

	/**
	 * Launch a select query from a nodegroup object
	 * @param ng   -- the nodegroup to execute a selection query from
	 * @param overrideConn -- the sparql connection rendered to JSON. please see com.ge.research.semtk.sparqlX.SparqlConnection for details.
	 * @param edcConstraintsJson -- the EDC Constraints rendered as JSON. expected format {\"@constraintSet\":{\"@op\":\"AND\",\"@constraints\":[]}} . these will be better documented in the future.
	 * @param runtimeConstraints -- the runtime constraints rendered as JSON. this is an array of JSON objects of the format 
	 * 									{"SparqlID" : "<value>", "Operator" : "<operator>", "Operands" : [<operands>] }
	 * 									for more details, please the package com.ge.research.semtk.belmont.runtimeConstraints .
	 * @return String jobId
	 */
	public String dispatchSelectFromNodeGroupToJobId(NodeGroup ng, SparqlConnection overrideConn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints) throws Exception{
		return this.dispatchSelectFromNodeGroupToJobId(ng, overrideConn, edcConstraintsJson, runtimeConstraints, null);
	}
	
	/**
	 * Launch a select query from a nodegroup object
	 * @param ng   -- the nodegroup to execute a selection query from
	 * @param overrideConn -- the sparql connection rendered to JSON. please see com.ge.research.semtk.sparqlX.SparqlConnection for details.
	 * @param edcConstraintsJson -- the EDC Constraints rendered as JSON. expected format {\"@constraintSet\":{\"@op\":\"AND\",\"@constraints\":[]}} . these will be better documented in the future.
	 * @param runtimeConstraints -- the runtime constraints rendered as JSON. this is an array of JSON objects of the format 
	 * 									{"SparqlID" : "<value>", "Operator" : "<operator>", "Operands" : [<operands>] }
	 * 									for more details, please the package com.ge.research.semtk.belmont.runtimeConstraints .
	 * @param flags
	 * @return jobId
	 * @throws Exception
	 */
	public String dispatchSelectFromNodeGroupToJobId(NodeGroup ng, SparqlConnection overrideConn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints, QueryFlags flags) throws Exception{
		SimpleResultSet ret = this.execDispatchSelectFromNodeGroup(ng, overrideConn, edcConstraintsJson, runtimeConstraints, flags);
		return ret.getResult("JobId");
	}
	
	/**
	 * Raw call to launch a select query from a nodegroup
	 * @param ng   -- the nodegroup to execute a selection query from
	 * @param overrideConn -- the sparql connection rendered to JSON. please see com.ge.research.semtk.sparqlX.SparqlConnection for details.
	 * @param edcConstraintsJson -- the EDC Constraints rendered as JSON. expected format {\"@constraintSet\":{\"@op\":\"AND\",\"@constraints\":[]}} . these will be better documented in the future.
	 * @param runtimeConstraints -- the runtime constraints rendered as JSON. this is an array of JSON objects of the format 
	 * 									{"SparqlID" : "<value>", "Operator" : "<operator>", "Operands" : [<operands>] }
	 * 									for more details, please the package com.ge.research.semtk.belmont.runtimeConstraints .
	 * @return SimpleResultSet with "JobId" field
	 * @throws Exception
	 */
	public SimpleResultSet execDispatchSelectFromNodeGroup(NodeGroup ng, SparqlConnection conn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints) throws Exception {
		return this.execDispatchSelectFromNodeGroup(ng, conn, edcConstraintsJson, runtimeConstraints, null);
	}
	
	/**
	 * Raw call to launch a select query from a nodegroup
	 * @param ng   -- the nodegroup to execute a selection query from
	 * @param overrideConn -- the sparql connection rendered to JSON. please see com.ge.research.semtk.sparqlX.SparqlConnection for details.
	 * @param edcConstraintsJson -- the EDC Constraints rendered as JSON. expected format {\"@constraintSet\":{\"@op\":\"AND\",\"@constraints\":[]}} . these will be better documented in the future.
	 * @param runtimeConstraints -- the runtime constraints rendered as JSON. this is an array of JSON objects of the format 
	 * 									{"SparqlID" : "<value>", "Operator" : "<operator>", "Operands" : [<operands>] }
	 * 									for more details, please the package com.ge.research.semtk.belmont.runtimeConstraints .
	 * @param flags
	 * @return SimpleResultSet with "JobId" field
	 * @throws Exception
	 */
	public SimpleResultSet execDispatchSelectFromNodeGroup(NodeGroup ng, SparqlConnection conn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints, QueryFlags flags) throws Exception{
	
		SimpleResultSet retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + dispatchSelectFromNodegroupEndpoint);
		this.parametersJSON.put(JSON_KEY_NODEGROUP, ng.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, conn.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_EDC_CONSTRAINTS, edcConstraintsJson == null ? null : edcConstraintsJson.toJSONString());	
		this.parametersJSON.put(JSON_KEY_RUNTIME_CONSTRAINTS,            runtimeConstraints == null ? null : runtimeConstraints.toJSONString());		
		this.parametersJSON.put(JSON_KEY_FLAGS,            flags == null ? null : flags.toJSONString());		
		
		try{
			retval = SimpleResultSet.fromJson((JSONObject) this.execute() );
			retval.throwExceptionIfUnsuccessful("Error at " + mappingPrefix + dispatchSelectFromNodegroupEndpoint);
		}
		finally{
			this.reset();
		}
		
		return retval;
	}
	
	/**
	 * launch a construct query from a a nodegroup
	 * @param ng
	 * @param conn
	 * @param edcConstraintsJson
	 * @param runtimeConstraints
	 * @return String jobId
	 * @throws Exception
	 */
	public String dispatchConstructFromNodeGroupToJobId(NodeGroup ng, SparqlConnection conn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints) throws Exception{
		SimpleResultSet ret = this.execDispatchConstructFromNodeGroup(ng, conn, edcConstraintsJson, runtimeConstraints);
		return ret.getResult("JobId");
	}
	

	
	@SuppressWarnings("unchecked")
	/**
	 * raw call to launch a construct query from a nodegroup
	 * @param ng
	 * @param conn
	 * @param edcConstraintsJson
	 * @param runtimeConstraints
	 * @return SimpleResultSet with "JobId" 
	 * @throws Exception
	 */
	public SimpleResultSet execDispatchConstructFromNodeGroup(NodeGroup ng, SparqlConnection conn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints) throws Exception{
		SimpleResultSet retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + dispatchConstructFromNodegroupEndpoint);
		this.parametersJSON.put(JSON_KEY_NODEGROUP, ng.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, conn.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_EDC_CONSTRAINTS, edcConstraintsJson == null ? null : edcConstraintsJson.toJSONString());	
		this.parametersJSON.put(JSON_KEY_RUNTIME_CONSTRAINTS,            runtimeConstraints == null ? null : runtimeConstraints.toJSONString());		
		
		try{
			retval = SimpleResultSet.fromJson((JSONObject) this.execute() );
			retval.throwExceptionIfUnsuccessful("Error at " + mappingPrefix + dispatchSelectFromNodegroupEndpoint);
		}
		finally{
			this.reset();
		}
		
		return retval;
	}	
	
	/**
	 * Run a construct query given a nodegroup
	 * @param ng
	 * @param conn
	 * @param edcConstraintsJson
	 * @param runtimeConstraints
	 * @return JSONObject with JSON-LD results
	 * @throws Exception
	 */
	public JSONObject dispatchConstructFromNodeGroup(NodeGroup ng, SparqlConnection conn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints) throws Exception{
		
		SimpleResultSet ret = this.execDispatchConstructFromNodeGroup(ng, conn, edcConstraintsJson, runtimeConstraints);
		
		return this.waitForJobAndGetJsonLd(ret.getResult("JobId"));
	}
	
	/**
	 * Run a delete query given a nodegroup
	 * @param ng
	 * @param conn
	 * @param edcConstraintsJson
	 * @param runtimeConstraints
	 * @return Table containing message
	 * @throws Exception
	 */
	public Table dispatchDeleteFromNodeGroup(NodeGroup ng, SparqlConnection conn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints) throws Exception{
		
		SimpleResultSet ret = this.execDispatchDeleteFromNodeGroup(ng, conn, edcConstraintsJson, runtimeConstraints);
		return this.waitForJobAndGetTable(ret.getResult("JobId"));
	}
	
	/**
	 * Run a select query given a nodegroup
	 * @param ng
	 * @param conn
	 * @param edcConstraintsJson
	 * @param runtimeConstraints
	 * @return Table of results
	 * @throws Exception
	 */
	public Table dispatchSelectFromNodeGroup(NodeGroup ng, SparqlConnection conn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints) throws Exception{
		
		SimpleResultSet ret = this.execDispatchSelectFromNodeGroup(ng, conn, edcConstraintsJson, runtimeConstraints, null);
		return this.waitForJobAndGetTable(ret.getResult("JobId"));
	}
	
	/**
	 * Run a select query given a nodegroup
	 * @param ng
	 * @param conn
	 * @param edcConstraintsJson
	 * @param runtimeConstraints
	 * @param flags
	 * @return Table of results
	 * @throws Exception
	 */
	public Table dispatchSelectFromNodeGroup(NodeGroup ng, SparqlConnection conn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints, QueryFlags flags) throws Exception{
		
		SimpleResultSet ret = this.execDispatchSelectFromNodeGroup(ng, conn, edcConstraintsJson, runtimeConstraints, flags);
		return this.waitForJobAndGetTable(ret.getResult("JobId"));
	}
	
	/**
	 * Run a select query given a nodegroup
	 * @param sgjson
	 * @param edcConstraintsJson
	 * @param runtimeConstraints
	 * @return Table of results
	 * @throws Exception
	 */
	public Table dispatchSelectFromNodeGroup(SparqlGraphJson sgjson, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints) throws Exception{
		return this.dispatchSelectFromNodeGroup(sgjson.getNodeGroup(), sgjson.getSparqlConn(), edcConstraintsJson, runtimeConstraints, null);
	}
	
	/**
	 * Run a select query given a nodegroup
	 * @param sgjson
	 * @param edcConstraintsJson
	 * @param runtimeConstraints
	 * @param flags
	 * @return Table of results
	 * @throws Exception
	 */
	public Table dispatchSelectFromNodeGroup(SparqlGraphJson sgjson, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints, QueryFlags flags) throws Exception{
		return this.dispatchSelectFromNodeGroup(sgjson.getNodeGroup(), sgjson.getSparqlConn(), edcConstraintsJson, runtimeConstraints, flags);
	}
	
	/**
	 * 	
	 * @param ng   -- the nodegroup to execute a selection query from
	 * @param conn -- the sparql connection rendered to JSON. please see com.ge.research.semtk.sparqlX.SparqlConnection for details.
	 * @param edcConstraintsJson -- the EDC Constraints rendered as JSON. expected format {\"@constraintSet\":{\"@op\":\"AND\",\"@constraints\":[]}} . these will be better documented in the future.
	 * @param runtimeConstraints -- the runtime constraints rendered as JSON. this is an array of JSON objects of the format 
	 * 									{"SparqlID" : "<value>", "Operator" : "<operator>", "Operands" : [<operands>] }
	 * 									for more details, please the package com.ge.research.semtk.belmont.runtimeConstraints .
	 * @return
	 */
	
	/**
	 * Launch a count query given a nodegroup
	 * @param ng
	 * @param conn
	 * @param edcConstraintsJson
	 * @param runtimeConstraints
	 * @return String jobId
	 * @throws Exception
	 */
	public String dispatchCountFromNodeGroupToJobId(NodeGroup ng, SparqlConnection conn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints) throws Exception{
		SimpleResultSet ret = this.execDispatchCountFromNodeGroup(ng, conn, edcConstraintsJson, runtimeConstraints);
		return ret.getResult("JobId");
	}
	
	/**
	 * Raw call to launch a count query given a nodegroup
	 * @param ng
	 * @param conn
	 * @param edcConstraintsJson
	 * @param runtimeConstraints
	 * @return SimpleResultSet containing "JobId"
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public SimpleResultSet execDispatchCountFromNodeGroup(NodeGroup ng, SparqlConnection conn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints) throws Exception{
		SimpleResultSet retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + dispatchCountFromNodegroupEndpoint);
		this.parametersJSON.put(JSON_KEY_NODEGROUP, ng.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, conn.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_EDC_CONSTRAINTS, edcConstraintsJson == null ? null : edcConstraintsJson.toJSONString());	
		this.parametersJSON.put(JSON_KEY_RUNTIME_CONSTRAINTS,            runtimeConstraints == null ? null : runtimeConstraints.toJSONString());		
		
		try{
			retval = SimpleResultSet.fromJson((JSONObject) this.execute() );
			retval.throwExceptionIfUnsuccessful();
		}
		finally{
			this.reset();
		}
		
		return retval;
	}
	
	/**
	 * Launch a delete query given a nodegroup
	 * @param ng
	 * @param conn
	 * @param edcConstraintsJson
	 * @param runtimeConstraints
	 * @return String jobId
	 * @throws Exception
	 */
	public String dispatchDeleteFromNodeGroupToJobId(NodeGroup ng, SparqlConnection conn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints) throws Exception{
		SimpleResultSet ret = this.execDispatchDeleteFromNodeGroup(ng, conn, edcConstraintsJson, runtimeConstraints);
		return ret.getResult("JobId");
	}
	
	/**
	 * Raw call to launch a delete query given a nodegroup
	 * @param ng
	 * @param conn
	 * @param edcConstraintsJson
	 * @param runtimeConstraints
	 * @return SimpleResultSet containing "JobId"
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public SimpleResultSet execDispatchDeleteFromNodeGroup(NodeGroup ng, SparqlConnection conn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints) throws Exception{
		SimpleResultSet retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + dispatchDeleteFromNodegroupEndpoint);
		this.parametersJSON.put(JSON_KEY_NODEGROUP, ng.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, conn.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_EDC_CONSTRAINTS, edcConstraintsJson == null ? null : edcConstraintsJson.toJSONString());	
		this.parametersJSON.put(JSON_KEY_RUNTIME_CONSTRAINTS,            runtimeConstraints == null ? null : runtimeConstraints.toJSONString());		
		
		try{
			retval = SimpleResultSet.fromJson((JSONObject) this.execute() );
			retval.throwExceptionIfUnsuccessful();
		}
		finally{
			this.reset();
		}
		
		return retval;
	}	
	
	/**
	 * 	
	 * @param ng   -- the nodegroup to execute a selection query from
	 * @param conn -- the sparql connection rendered to JSON. please see com.ge.research.semtk.sparqlX.SparqlConnection for details.
	 * @param edcConstraintsJson -- the EDC Constraints rendered as JSON. expected format {\"@constraintSet\":{\"@op\":\"AND\",\"@constraints\":[]}} . these will be better documented in the future.
	 * @param runtimeConstraints -- the runtime constraints rendered as JSON. this is an array of JSON objects of the format 
	 * 									{"SparqlID" : "<value>", "Operator" : "<operator>", "Operands" : [<operands>] }
	 * 									for more details, please the package com.ge.research.semtk.belmont.runtimeConstraints .
	 * @param targetObjectSparqlId -- the ID of the object to filter for valid values of. these are the sparql IDs used in the nodegroup.
	 * @return
	 */
	
	/**
	 * Launch a filter query given a nodegroup
	 * @param ng
	 * @param targetObjectSparqlId
	 * @param conn
	 * @param edcConstraintsJson
	 * @param runtimeConstraints
	 * @return String jobId
	 * @throws Exception
	 */
	public String dispatchFilterFromNodeGroupToJobId(NodeGroup ng, String targetObjectSparqlId, SparqlConnection conn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints) throws Exception{
		SimpleResultSet ret = this.execDispatchFilterFromNodeGroup(ng, targetObjectSparqlId, conn, edcConstraintsJson, runtimeConstraints);
		return ret.getResult("JobId");
	}
	
	/**
	 * Raw call to launch a filter query given a nodegroup
	 * @param ng
	 * @param targetObjectSparqlId
	 * @param conn
	 * @param edcConstraintsJson
	 * @param runtimeConstraints
	 * @return SimpleResultSet containing "JobId"
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public SimpleResultSet execDispatchFilterFromNodeGroup(NodeGroup ng, String targetObjectSparqlId, SparqlConnection conn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints) throws Exception{
		SimpleResultSet retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + dispatchFilterByIdEndpoint);
		this.parametersJSON.put(JSON_KEY_NODEGROUP, ng.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, conn.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_EDC_CONSTRAINTS, edcConstraintsJson == null ? null : edcConstraintsJson.toJSONString());	
		this.parametersJSON.put(JSON_KEY_RUNTIME_CONSTRAINTS,            runtimeConstraints == null ? null : runtimeConstraints.toJSONString());		
		this.parametersJSON.put("targetObjectSparqlId", targetObjectSparqlId);
		
		try{
			retval = SimpleResultSet.fromJson((JSONObject) this.execute() );
			retval.throwExceptionIfUnsuccessful();
		}
		finally{
			this.reset();
		}
		
		return retval;
	}	
	
	
	/**
	 * raw call to get ingestion columns given nodegroup id
	 * @param nodegroupID
	 * @return SimpleResultSet with 'columnNames' string array 
	 * @throws Exception
	 */
	public SimpleResultSet execGetIngestionColumnsById(String nodegroupID) throws Exception {
		SimpleResultSet retval = new SimpleResultSet();
		
		conf.setServiceEndpoint(mappingPrefix + getIngestionColumnsById);
		this.parametersJSON.put(JSON_KEY_ID, nodegroupID);
		
		try{
			retval.readJson((JSONObject) this.execute() );
			retval.throwExceptionIfUnsuccessful();
		}
		finally{
			this.reset();
		}
		
		return retval;
	}
	
	/**
	 * get ingestion columns given nodegroup id
	 * @param nodegroupID
	 * @return array of ingestion column names
	 * @throws Exception
	 */
	public String [] getIngestionColumnsById(String nodegroupID) throws Exception {
		SimpleResultSet res = this.execGetIngestionColumnsById(nodegroupID);
		res.throwExceptionIfUnsuccessful();
		return res.getResultStringArray("columnNames");
	}
	
	/**
	 * raw call to get runtime constraint sparqlIDs given nodegroup id
	 * @param nodegroupID
	 * @return table result set of 'valueId', 'itemType', 'valueType'
	 * @throws Exception
	 */
	public TableResultSet execGetRuntimeConstraintsByNodeGroupID(String nodegroupID) throws Exception {
		TableResultSet retval = new TableResultSet();
		
		conf.setServiceEndpoint(mappingPrefix + getRuntimeConstraintsByNodeGroupID);
		this.parametersJSON.put(JSON_KEY_NODEGROUP_ID, nodegroupID);
		
		try{
			retval.readJson((JSONObject) this.execute() );
			retval.throwExceptionIfUnsuccessful();
		}
		finally{
			this.reset();
		}
		
		return retval;
	}
	
	/**
	 * get runtime constraint sparqlIDs given nodegroup id
	 * @param nodegroupID
	 * @return table of 'valueId', 'itemType', 'valueType'
	 * @throws Exception
	 */
	public Table getRuntimeConstraintsByNodeGroupID(String nodegroupID) throws Exception {
		TableResultSet res = this.execGetRuntimeConstraintsByNodeGroupID(nodegroupID);
		res.throwExceptionIfUnsuccessful();
		return res.getTable();
	}

// Execute Dispatch maintained for backward compatibility -- they are largely replaced by the "Select" variants...
/**
 * 	
 * @param nodegroupID    -- string ID for the nodegroup to be executed. this assumes that the node group resides in a nodegroup store that was config'd on the far end (service)
 * @param overrideConn -- the sparql connection rendered to JSON. please see com.ge.research.semtk.sparqlX.SparqlConnection for details.
 * @param edcConstraintsJson -- the EDC Constraints rendered as JSON. expected format {\"@constraintSet\":{\"@op\":\"AND\",\"@constraints\":[]}} . these will be better documented in the future.
 * @param flagsJson -- an array of flag strings rendered as a JSON array (e.g. ["RDB_QUERYGEN_OMIT_ALIASES"])
 * @param runtimeConstraints -- the runtime constraints rendered as JSON. this is an array of JSON objects of the format 
 * 									{"SparqlID" : "<value>", "Operator" : "<operator>", "Operands" : [<operands>] }
 * 									for more details, please the package com.ge.research.semtk.belmont.runtimeConstraints .
 * @return
 */

	/**
	 * Launch a select query given a nodegroupID
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param flagsJson
	 * @param runtimeConstraintsJson
	 * @param limitOverride
	 * @param offsetOverride
	 * @return String jobId
	 * @throws Exception
	 */
	public String dispatchByIdWithToJobId(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray flagsJson, JSONArray runtimeConstraintsJson, int limitOverride, int offsetOverride) throws Exception{
		SimpleResultSet ret =  this.execDispatchById(nodegroupID, overrideConn, edcConstraintsJson, flagsJson, runtimeConstraintsJson, limitOverride, offsetOverride);
		return ret.getResult("JobId");
	}
	
	/**
	 * Launch a select query given a nodegroupID
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @return String jobId
	 * @throws Exception
	 */
	public String dispatchByIdToJobId(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson) throws Exception{
		SimpleResultSet ret =  this.execDispatchById(nodegroupID, overrideConn, edcConstraintsJson, runtimeConstraintsJson);
		return ret.getResult("JobId");
	}
	
	/**
	 * Raw call to launch a select query
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param runtimeConstraintsJson
	 * @return SimpleResultSet containing "JobId"
	 * @throws Exception
	 */
	public SimpleResultSet execDispatchById(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray runtimeConstraintsJson) throws Exception{
		return this.execDispatchById(nodegroupID, overrideConn, edcConstraintsJson, null, runtimeConstraintsJson, -1, -1);
	}
	
	/**
	 * Raw call to launch a select query
	 * @param nodegroupID
	 * @param overrideConn
	 * @param edcConstraintsJson
	 * @param flagsJson
	 * @param runtimeConstraintsJson
	 * @param limitOverride
	 * @param offsetOverride
	 * @return SimpleResultSet containing "JobId"
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public SimpleResultSet execDispatchById(String nodegroupID, SparqlConnection overrideConn, JSONObject edcConstraintsJson, JSONArray flagsJson, JSONArray runtimeConstraintsJson, int limitOverride, int offsetOverride) throws Exception{
		SimpleResultSet retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + dispatchByIdEndpoint);
		this.parametersJSON.put(JSON_KEY_NODEGROUP_ID, nodegroupID);
		this.parametersJSON.put(JSON_KEY_LIMIT_OVERRIDE, limitOverride);
		this.parametersJSON.put(JSON_KEY_OFFSET_OVERRIDE, offsetOverride);

		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, overrideConn.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_EDC_CONSTRAINTS, edcConstraintsJson == null ? null : edcConstraintsJson.toJSONString());	
		this.parametersJSON.put(JSON_KEY_FLAGS, flagsJson == null ? null : flagsJson.toJSONString());
		this.parametersJSON.put(JSON_KEY_RUNTIME_CONSTRAINTS,            runtimeConstraintsJson == null ? null : runtimeConstraintsJson.toJSONString());		
		
		try{
			LocalLogger.logToStdErr("sending executeDispatchById request");
			retval = SimpleResultSet.fromJson((JSONObject) this.execute() );
			retval.throwExceptionIfUnsuccessful();
		}
		finally{
			this.reset();
		}
		LocalLogger.logToStdErr("executeDispatchById request finished without exception");
		return retval;
	}
	
	
	
	/**
	 * 	
	 * @param ng   -- the nodegroup to execute a selection query from
	 * @param overrideConn -- the sparql connection rendered to JSON. please see com.ge.research.semtk.sparqlX.SparqlConnection for details.
	 * @param edcConstraintsJson -- the EDC Constraints rendered as JSON. expected format {\"@constraintSet\":{\"@op\":\"AND\",\"@constraints\":[]}} . these will be better documented in the future.
	 * @param runtimeConstraints -- the runtime constraints rendered as JSON. this is an array of JSON objects of the format 
	 * 									{"SparqlID" : "<value>", "Operator" : "<operator>", "Operands" : [<operands>] }
	 * 									for more details, please the package com.ge.research.semtk.belmont.runtimeConstraints .
	 * @return
	 */
	
	/**
	 * Launch a select given a nodegroup
	 * @param ng
	 * @param conn
	 * @param edcConstraintsJson
	 * @param runtimeConstraints
	 * @return String jobId
	 * @throws Exception
	 */
	public String dispatchFromNodeGroupToJobId(NodeGroup ng, SparqlConnection conn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints) throws Exception{
		SimpleResultSet ret = this.execDispatchFromNodeGroup(ng, conn, edcConstraintsJson, runtimeConstraints);
		return ret.getResult("JobId");
	}
	
	/**
	 * Raw call to launch a select query given a nodegroup
	 * @param ng
	 * @param conn
	 * @param edcConstraintsJson
	 * @param runtimeConstraints
	 * @return SimpleResultSet containing "JobId"
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public SimpleResultSet execDispatchFromNodeGroup(NodeGroup ng, SparqlConnection conn, JSONObject edcConstraintsJson, RuntimeConstraintManager runtimeConstraints) throws Exception{
		SimpleResultSet retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + dispatchFromNodegroupEndpoint);
		this.parametersJSON.put(JSON_KEY_NODEGROUP, ng.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, conn.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_EDC_CONSTRAINTS, edcConstraintsJson == null ? null : edcConstraintsJson.toJSONString());	
		this.parametersJSON.put(JSON_KEY_RUNTIME_CONSTRAINTS,            runtimeConstraints == null ? null : runtimeConstraints.toJSONString());		
		
		try{
			retval = SimpleResultSet.fromJson((JSONObject) this.execute() );
			retval.throwExceptionIfUnsuccessful();
		}
		finally{
			this.reset();
		}
		
		return retval;
	}

	/**
	 * Raw call to ingest by nodegroup id
	 * NOTE this is truly synchronous and could time out
	 * @param nodegroupAndTemplateId
	 * @param csvContentStr
	 * @param overrideConn
	 * @return RecordProcessResults
	 * @throws Exception
	 */
	public RecordProcessResults execIngestionFromCsvStr(String nodegroupAndTemplateId, String csvContentStr, SparqlConnection overrideConn) throws Exception {
		return this.execIngestionFromCsvStrNewConnection(nodegroupAndTemplateId, csvContentStr, overrideConn);
	}
	
	/**
	 * Raw call to ingest by nodegroup id
	 * NOTE this is truly synchronous and could time out
	 * @param nodegroupAndTemplateId
	 * @param csvContentStr
	 * @param overrideConn
	 * @return RecordProcessResults
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public RecordProcessResults execIngestionFromCsvStrNewConnection(String nodegroupAndTemplateId, String csvContentStr, SparqlConnection overrideConn) throws Exception {
		RecordProcessResults retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + ingestFromCsvStringsNewConnectionEndpoint);
		this.parametersJSON.put("templateId", nodegroupAndTemplateId);
		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, overrideConn.toJson().toJSONString());
		this.parametersJSON.put("csvContent", csvContentStr);
	
		try{
			JSONObject jobj = (JSONObject) this.execute();
			retval = new RecordProcessResults(jobj);
			retval.throwExceptionIfUnsuccessful();
		}
		finally{
			this.reset();
		}
		return retval;
	}
	
	/**
	 * Raw call to ingest by nodegroup id
	 * NOTE this is truly synchronous and could time out
	 * @param nodegroupAndTemplateId
	 * @param csvContentStr
	 * @param overrideConn
	 * @return RecordProcessResults
	 * @throws Exception
	 */
	public RecordProcessResults execIngestionFromCsvStrById(String nodegroupAndTemplateId, String csvContentStr, SparqlConnection overrideConn) throws Exception {
		return execIngestionFromCsvStrNewConnection(nodegroupAndTemplateId, csvContentStr, overrideConn);
	}
	
	/**
	 * Raw call to ingest by nodegroup id
	 * NOTE this is truly synchronous and could time out
	 * @param nodegroupAndTemplateId
	 * @param csvContentStr
	 * @param overrideConn
	 * @param trackFlag - boolean should load tracking be used
	 * @param overrideBaseURI - override for the baseURI prepended to all URI's ingested. e.g. "http://base" or "$TRACK_KEY"
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public RecordProcessResults execIngestionFromCsvStrNewConnection(String nodegroupAndTemplateId, String csvContentStr, SparqlConnection overrideConn, boolean trackFlag, String overrideBaseURI) throws Exception {
		RecordProcessResults retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + ingestFromCsvStringsNewConnectionEndpoint);
		this.parametersJSON.put("templateId", nodegroupAndTemplateId);
		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, overrideConn.toJson().toJSONString());
		this.parametersJSON.put("csvContent", csvContentStr);
		this.parametersJSON.put("trackFlag", trackFlag);
		this.parametersJSON.put("overrideBaseURI", overrideBaseURI);
		try{
			JSONObject jobj = (JSONObject) this.execute();
			retval = new RecordProcessResults(jobj);
			retval.throwExceptionIfUnsuccessful();
		}
		finally{
			this.reset();
		}
		return retval;
	}
	/**
	 * launch an ingestion job by nodegroup id
	 * @param nodegroupAndTemplateId
	 * @param csvContentStr
	 * @param overrideConn
	 * @return jobId string
	 * @throws Exception if call is unsuccessful
	 */
	@SuppressWarnings("unchecked")
	public String execIngestFromCsvStringsByIdAsync(String nodegroupAndTemplateId, String csvContentStr, SparqlConnection overrideConn) throws Exception {
		
		conf.setServiceEndpoint(mappingPrefix + ingestFromCsvStringsByIdAsyncEndpoint);
		this.parametersJSON.put("templateId", nodegroupAndTemplateId);
		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, overrideConn.toJson().toJSONString());
		this.parametersJSON.put("csvContent", csvContentStr);
	
		try{
			JSONObject jobj = (JSONObject) this.execute();
			SimpleResultSet retval = SimpleResultSet.fromJson(jobj);
			retval.throwExceptionIfUnsuccessful();
			return retval.getResult(SimpleResultSet.JOB_ID_RESULT_KEY);
		}
		finally{
			this.reset();
		}
	}
	
	/**
	 * launch an ingestion job by nodegroup id
	 * @param nodegroupAndTemplateId
	 * @param csvContentStr
	 * @param overrideConn
	 * @return jobId string
	 * @throws Exception if call is unsuccessful
	 */
	@SuppressWarnings("unchecked")
	public String execIngestFromCsvStringsByIdAsync(String nodegroupAndTemplateId, String csvContentStr, SparqlConnection overrideConn, boolean trackFlag, String overrideBaseURI) throws Exception {
		
		conf.setServiceEndpoint(mappingPrefix + ingestFromCsvStringsByIdAsyncEndpoint);
		this.parametersJSON.put("templateId", nodegroupAndTemplateId);
		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, overrideConn.toJson().toJSONString());
		this.parametersJSON.put("csvContent", csvContentStr);
		this.parametersJSON.put("trackFlag", trackFlag);
		this.parametersJSON.put("overrideBaseURI", overrideBaseURI);
		
		try{
			JSONObject jobj = (JSONObject) this.execute();
			SimpleResultSet retval = SimpleResultSet.fromJson(jobj);
			retval.throwExceptionIfUnsuccessful();
			return retval.getResult(SimpleResultSet.JOB_ID_RESULT_KEY);
		}
		finally{
			this.reset();
		}
	}
	
	/**
	 * Launch ingestion by SparqlGraphJson 
	 * @param sgjsonWithOverride - contains nodegroup, ingest template, and connection
	 * @param csvContentStr
	 * @return jobId
	 * @throws Exception
	 */
	public String execIngestFromCsvStringsAndTemplateAsync(SparqlGraphJson sgjsonWithOverride, String csvContentStr, boolean trackFlag, String overrideBaseURI) throws Exception {
		conf.setServiceEndpoint(mappingPrefix + ingestFromCsvStringsAndTemplateAsync);
		this.parametersJSON.put("template", sgjsonWithOverride.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, sgjsonWithOverride.getSparqlConn().toJson().toJSONString());
		this.parametersJSON.put("csvContent", csvContentStr);
		this.parametersJSON.put("trackFlag", trackFlag);
		this.parametersJSON.put("overrideBaseURI", overrideBaseURI);
		
		try{
			JSONObject jobj = (JSONObject) this.execute();
			SimpleResultSet retval = SimpleResultSet.fromJson(jobj);
			retval.throwExceptionIfUnsuccessful();
			return retval.getResult(SimpleResultSet.JOB_ID_RESULT_KEY);
		}
		finally{
			this.reset();
		}
	}

	/**
	 * Launch ingest from a nodegroup
	 * @param sgjsonWithOverride
	 * @param csvContentStr
	 * @return jobId
	 * @throws Exception
	 */
	public String execIngestFromCsvStringsAndTemplateAsync(SparqlGraphJson sgjsonWithOverride, String csvContentStr) throws Exception {
		conf.setServiceEndpoint(mappingPrefix + ingestFromCsvStringsAndTemplateAsync);
		this.parametersJSON.put("template", sgjsonWithOverride.toJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, sgjsonWithOverride.getSparqlConn().toJson().toJSONString());
		this.parametersJSON.put("csvContent", csvContentStr);
	
		try{
			JSONObject jobj = (JSONObject) this.execute();
			SimpleResultSet retval = SimpleResultSet.fromJson(jobj);
			retval.throwExceptionIfUnsuccessful();
			return retval.getResult(SimpleResultSet.JOB_ID_RESULT_KEY);
		}
		finally{
			this.reset();
		}
	}
	
	
	


	/**
	 * Launch asynchronous ingestion from from nodegroupID and wait for results
	 * @param nodegroupAndTemplateId
	 * @param csvContentStr
	 * @param overrideConn =
	 * @return String success message
	 * @throws Exception on failure
	 */
	public String dispatchIngestFromCsvStringsByIdSync(String nodegroupAndTemplateId, String csvContentStr, SparqlConnection overrideConn) throws Exception {
		String jobId = this.execIngestFromCsvStringsByIdAsync(nodegroupAndTemplateId, csvContentStr, overrideConn);
		this.waitForCompletion(jobId);
		if (this.getJobSuccess(jobId)) {
			return this.getJobStatusMessage(jobId);
		} else {
			throw new Exception("Ingestion failed:\n" + this.getResultsTable(jobId).toCSVString());
		}
	}
	
	/**
	 * Launch asynchronous ingestion from from nodegroupID and wait for results
	 * @param nodegroupAndTemplateId
	 * @param csvContentStr
	 * @return String success message
	 * @throws Exception on failure
	 */
	public String dispatchIngestFromCsvStringsByIdSync(String nodegroupAndTemplateId, String csvContentStr) throws Exception {
		return this.dispatchIngestFromCsvStringsByIdSync(nodegroupAndTemplateId, csvContentStr, NodeGroupExecutor.get_USE_NODEGROUP_CONN());
	}
	
	/**
	 * Launch asynchronous ingest with sgjson and csv string and wait for results
	 * @param sparqlGraphJson
	 * @param csvContentStr
	 * @return success message
	 * @throws Exception on failure
	 */
	public String dispatchIngestFromCsvStringsSync(SparqlGraphJson sparqlGraphJson, String csvContentStr) throws Exception {
		String jobId = execIngestFromCsvStringsAndTemplateAsync(sparqlGraphJson, csvContentStr);
		this.waitForCompletion(jobId);
		if (this.getJobSuccess(jobId)) {
			return this.getJobStatusMessage(jobId);
		} else {
			throw new Exception("Ingestion failed:\n" + this.getResultsTable(jobId).toCSVString());
		}
	}
	
	/**
	 * Raw call to a synchronous ingestion given nodegroup
	 * NOTE this is truly synchronous and could time out
	 * @param sparqlGraphJson
	 * @param csvContentStr
	 * @param trackFlag - boolean should load tracking be used
	 * @param overrideBaseURI - override for the baseURI prepended to all URI's ingested. e.g. "http://base" or "$TRACK_KEY"
	 * @return RecordProcessResults
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public RecordProcessResults execIngestionFromCsvStr(SparqlGraphJson sparqlGraphJson, String csvContentStr, boolean trackFlag, String overrideBaseURI) throws Exception {
		RecordProcessResults retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + ingestFromCsvStringsAndTemplateNewConnectionEndpoint);
		this.parametersJSON.put("template", sparqlGraphJson.getJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, sparqlGraphJson.getSparqlConnJson().toJSONString());
		this.parametersJSON.put("csvContent", csvContentStr);
		this.parametersJSON.put("trackFlag", trackFlag);
		this.parametersJSON.put("overrideBaseURI", overrideBaseURI);
		
		try{
			JSONObject jobj = (JSONObject) this.execute();
			retval = new RecordProcessResults(jobj);
			retval.throwExceptionIfUnsuccessful();
		}
		finally{
			this.reset();
		}
		return retval;
	}

	/**
	 * Raw call to a synchronous ingestion given nodegroup
	 * NOTE this is truly synchronous and could time out
	 * @param sparqlGraphJson
	 * @param csvContentStr
	 * @return RecordProcessResults
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	public RecordProcessResults execIngestionFromCsvStr(SparqlGraphJson sparqlGraphJson, String csvContentStr) throws Exception {
		RecordProcessResults retval = null;
		
		conf.setServiceEndpoint(mappingPrefix + ingestFromCsvStringsAndTemplateNewConnectionEndpoint);
		this.parametersJSON.put("template", sparqlGraphJson.getJson().toJSONString());
		this.parametersJSON.put(JSON_KEY_SPARQL_CONNECTION, sparqlGraphJson.getSparqlConnJson().toJSONString());
		this.parametersJSON.put("csvContent", csvContentStr);
	
		try{
			JSONObject jobj = (JSONObject) this.execute();
			retval = new RecordProcessResults(jobj);
			retval.throwExceptionIfUnsuccessful();
		}
		finally{
			this.reset();
		}
		return retval;
	}

	/**
	 * Read sgjson from disk, apply runtime constraints, launch select 
	 * @param resourcePath
	 * @param jarObj
	 * @param conn
	 * @return SimpleResultSet containing "JobId"
	 * @throws Exception
	 */
	public SimpleResultSet execDispatchSelectFromNodeGroupResource(String resourcePath, Object jarObj, SparqlConnection conn) throws Exception {
		return this.execDispatchSelectFromNodeGroupResource(resourcePath, jarObj, conn, null);
		
	}
	
	/**
	 * Read sgjson from disk, apply runtime constraints, launch select 
	 * @param resourcePath
	 * @param jarObj
	 * @param conn
	 * @param runtimeConstraintsJson
	 * @return SimpleResultSet containing "JobId"
	 * @throws Exception
	 */
	public SimpleResultSet execDispatchSelectFromNodeGroupResource(String resourcePath, Object jarObj, SparqlConnection conn, JSONArray runtimeConstraintsJson) throws Exception {
		
		SparqlGraphJson sgjson = new SparqlGraphJson(Utility.getResourceAsJson(jarObj, resourcePath));
		NodeGroup ng = sgjson.getNodeGroup();
		if (runtimeConstraintsJson != null) {
			RuntimeConstraintManager manager = new RuntimeConstraintManager(ng);
			manager.applyConstraintJson(runtimeConstraintsJson);
		}
		return this.execDispatchSelectFromNodeGroup(ng, conn, null, null);
	}
	
	public Table dispatchSelectFromNodeGroupResourceToTable(String resourcePath, Object jarObj, SparqlConnection conn) throws Exception {
		
		SparqlGraphJson sgjson = new SparqlGraphJson(Utility.getResourceAsJson(jarObj, resourcePath));
		sgjson.setSparqlConn(conn);
		return this.dispatchSelectFromNodeGroup(sgjson, null, null);
		
	}
	
	/**
	 * Launch a delete query from a json resource and wait for results
	 * @param resourcePath - path to nodegroup json file
	 * @param jarObj
	 * @param conn
	 * @param runtimeConstraintsJson
	 * @return successful status message
	 * @throws Exception
	 */
	public String dispatchDeleteFromNodeGroupResource(String resourcePath, Object jarObj, SparqlConnection conn, JSONArray runtimeConstraintsJson) throws Exception {
		SimpleResultSet res = this.execDispatchDeleteFromNodeGroupResource(resourcePath, jarObj, conn, runtimeConstraintsJson);
		String jobId = res.getJobId();
		this.waitForCompletion(jobId);
		if (this.getJobSuccess(jobId)) {
			return this.getJobStatusMessage(jobId);
		} else {
			throw new Exception("Ingestion failed:\n" + this.getResultsTable(jobId).toCSVString());
		}
	}
	/**
	 * Launch a delete query from a json resource 
	 * @param resourcePath
	 * @param jarObj
	 * @param conn
	 * @param runtimeConstraintsJson
	 * @return SimpleResultSet containing jobId
	 * @throws Exception
	 */
	public SimpleResultSet execDispatchDeleteFromNodeGroupResource(String resourcePath, Object jarObj, SparqlConnection conn, JSONArray runtimeConstraintsJson) throws Exception {
		
		SparqlGraphJson sgjson = new SparqlGraphJson(Utility.getResourceAsJson(jarObj, resourcePath));
		NodeGroup ng = sgjson.getNodeGroup();
		if (runtimeConstraintsJson != null) {
			RuntimeConstraintManager manager = new RuntimeConstraintManager(ng);
			manager.applyConstraintJson(runtimeConstraintsJson);
		}
		return this.execDispatchDeleteFromNodeGroup(ng, conn, null, null);
	}
	
}
