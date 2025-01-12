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

import java.util.HashMap;
import java.util.UUID;

import com.ge.research.semtk.belmont.Node;
import com.ge.research.semtk.belmont.NodeGroup;
import com.ge.research.semtk.edc.client.OntologyInfoClient;
import com.ge.research.semtk.edc.client.ResultsClient;
import com.ge.research.semtk.edc.client.ResultsClientConfig;
import com.ge.research.semtk.fdc.FdcClient;
import com.ge.research.semtk.fdc.FdcClientConfig;
import com.ge.research.semtk.load.DataLoader;
import com.ge.research.semtk.load.utility.SparqlGraphJson;
import com.ge.research.semtk.nodeGroupStore.client.NodeGroupStoreRestClient;
import com.ge.research.semtk.resultSet.Table;
import com.ge.research.semtk.resultSet.TableResultSet;
import com.ge.research.semtk.sparqlX.SparqlConnection;
import com.ge.research.semtk.sparqlX.SparqlEndpointInterface;
import com.ge.research.semtk.sparqlX.asynchronousQuery.DispatcherSupportedQueryTypes;
import com.ge.research.semtk.utility.LocalLogger;


/**
 * 
 * @author 200001934
 *
 */
public class FdcDispatcher extends EdcDispatcher {
	
	private FdcServiceManager fdcServiceManager = null;
	private String tmpGraphUser = null;
	private String tmpGraphPassword = null;
	private SparqlEndpointInterface extConfigSei;
	private OntologyInfoClient oInfoClient = null;
	
	public FdcDispatcher(String jobId, SparqlGraphJson sgJson, SparqlEndpointInterface jobTrackerSei, ResultsClientConfig resConfig, SparqlEndpointInterface extConfigSei, boolean heedRestrictions, OntologyInfoClient oInfoClient, NodeGroupStoreRestClient ngStoreClient) throws Exception{
		
		super(jobId, sgJson, jobTrackerSei, resConfig, extConfigSei, false, oInfoClient, ngStoreClient);
		
		try {
			this.fdcServiceManager = new FdcServiceManager(extConfigSei, this.queryNodeGroup, this.oInfo, jobId, this.querySei, oInfoClient);
		} catch (FdcConfigException e) {
			// on failure: try reloading the FdcCache
			FdcServiceManager.cacheFdcConfig(extConfigSei, oInfoClient);
			this.fdcServiceManager = new FdcServiceManager(extConfigSei, this.queryNodeGroup, this.oInfo, jobId, this.querySei, oInfoClient);
		}
		
		this.tmpGraphUser = extConfigSei.getUserName();
		this.tmpGraphPassword = extConfigSei.getPassword();
		this.extConfigSei = extConfigSei;
		this.oInfoClient = oInfoClient;
	}
	/**
	 * 
	 * 
	 * @throws Exception 
	 */
	@Override
	public void execute(Object extConstraintsJsonObj, Object flagsObj, DispatcherSupportedQueryTypes qt, String targetSparqlID) {
		TableResultSet retval = null; // expect this to get instantiated with the appropriate subclass.		
		
		try {
			if (this.fdcServiceManager.isFdc()) {
				// both fdc and edc
				if (this.dispatchServiceMgr.getServiceMnemonic() != null) {
					String msg = "Can not execute query on nodegroup with both EDC mnemonic: " + 
							this.dispatchServiceMgr.getServiceMnemonic() + 
							" and FDC nodes: " +
							String.join(", ", this.fdcServiceManager.getFdcNodeSparqlIDs())
							;
					this.updateStatusToFailed(msg);
				// fdc
				} else {
					try {
						this.executeFdc(qt, targetSparqlID);
					} catch (Exception e) {
						// on failure: try reloading the FdcCache
						FdcServiceManager.cacheFdcConfig(extConfigSei, oInfoClient);
						this.executeFdc(qt, targetSparqlID);
					}
					
				}
			} else {
				// EDC or plain queries
				super.execute(extConstraintsJsonObj, flagsObj, qt, targetSparqlID);
			}
		} catch (Exception e) {
			this.updateStatusToFailed(e.getLocalizedMessage());
			LocalLogger.printStackTrace(e);
		}
	}
	
	/**
	 * Execute query for known FDC nodegroup
	 */
	private void executeFdc(DispatcherSupportedQueryTypes qt, String targetSparqlID) {
		String errorHeader = "";

		try {
	
			long startMsec = System.currentTimeMillis();
			
			SparqlEndpointInterface tempDataSei = this.createTempSei();
			SparqlConnection ingestConn = this.createIngestConn(tempDataSei);
			SparqlConnection expandedConn = this.createExpandedConn(tempDataSei);
			
			int statusIncrement = 99 / (4 * this.fdcServiceManager.getFdcNodeCount() + 1);
			int percentComplete = 1;
			
			// loop through FDC nodes
			// TODO: this should be smarter and manage dependencies/order 
			HashMap<String, Table> paramSetTables = new HashMap<String, Table>();
			while (this.fdcServiceManager.nextFdcNode()) {
				Node node = this.fdcServiceManager.getCurrentFdcNode();
				String fdcName = node.getSparqlID();
				errorHeader = "setting up FDC node " + fdcName;
				
				// get and execute each param nodegroup
				// TODO this could break queries into LIMIT/OFFSET chunks
				// TODO this might be async
				
				this.jobTracker.setJobPercentComplete(this.jobID, percentComplete, "FDC Query: " + fdcName + " - getting inputs " );
				
				HashMap<String, NodeGroup> paramSetNodeGroups = this.fdcServiceManager.getParamNodeGroups(node);
				for (String paramSet : paramSetNodeGroups.keySet()) {
					errorHeader = "querying " + fdcName + " param set " + paramSet;
					NodeGroup ng = paramSetNodeGroups.get(paramSet);
					
					if (ng == null) {
						// if nodegroup doesn't have necessary subgraph to generate param
						paramSetTables.put(paramSet, null);
						break;
						
					} else {
						// if param can be calculated with nodegroup, do so
						ng.setSparqlConnection(expandedConn);
						String query = ng.generateSparqlSelect();
						paramSetTables.put(paramSet, this.querySei.executeQueryToTable(query));
					}
				}
				
				percentComplete += statusIncrement;   // 1 of FDC - finished retrieval
				this.jobTracker.setJobPercentComplete(this.jobID, percentComplete, "FDC Query: " + fdcName + " - running" );
				
				// log inputs
				LocalLogger.logToStdErr("FDC call: " + this.fdcServiceManager.getCurrentServiceUrl());
				//for (Table t : paramSetTables.values()) {
				//	LocalLogger.logToStdErr("Input table: \n" + (t == null ? "null" : t.toCSVString()));
				//}
				
				// if nodegroup had subgraphs available to generate all parameter tables
				// run the FDC service
				if (! paramSetTables.containsValue(null)) {
					errorHeader = "running fdc service " + this.fdcServiceManager.getCurrentServiceUrl();
					
					// execute FDC call
					FdcClient fdcClient = new FdcClient(FdcClientConfig.fromFullEndpoint(this.fdcServiceManager.getCurrentServiceUrl(), paramSetTables));
					TableResultSet fdcResultSet = fdcClient.executeWithTableResultReturn();
					fdcResultSet.throwExceptionIfUnsuccessful("Error making FDC call");
					Table fdcResults = fdcResultSet.getTable();
					String fdcCsv = fdcResults.toCSVString();
					
					// log outputs
					LocalLogger.logToStdOut("FDC results returned " + String.valueOf(fdcResults.getNumRows()) + " rows");
					
					// ingest
					percentComplete += statusIncrement; // 2 of FDC - finished running FDC client
					this.jobTracker.setJobPercentComplete(this.jobID, percentComplete, "FDC Query: " + fdcName + " - ingesting " + String.valueOf(fdcResults.getNumRows()) + " rows of results" );
					
					String ingestId = this.fdcServiceManager.getCurrentIngestNodegroupId();
					errorHeader = "ingesting fdc results using " + ingestId;
					
					// try retrieving nodegroup from fdcClient first, failures will be silent
					FdcClient fdcGetNgClient = new FdcClient(FdcClientConfig.buildGetNodegroup(this.fdcServiceManager.getCurrentServiceUrl(), ingestId));
					SparqlGraphJson sgJson = fdcGetNgClient.executeGetNodegroup();
					if (sgJson == null) {
						// try nodegroup store if not found yet.  This one will throw an exception on failure.
						sgJson = this.ngStoreClient.executeGetNodeGroupByIdToSGJson(ingestId);
					}
					
					sgJson.setSparqlConn(ingestConn);
					boolean precheck = false;
					DataLoader.loadFromCsvString(sgJson.getJson(), fdcCsv, this.tmpGraphUser, this.tmpGraphPassword, precheck);	
				}
				percentComplete += 2 * statusIncrement; // 3 and 4 of FDC - finished ingestion
			}
			
			errorHeader = "running final sparql query";

			// run regular query
			this.jobTracker.setJobPercentComplete(this.jobID, percentComplete, "FDC Query: running final query" );
			
			this.queryNodeGroup.setSparqlConnection(expandedConn);
			
			String query = this.getSparqlQuery(qt, targetSparqlID);
			this.executePlainSparqlQuery(query, qt);
			
			//--- At this point results have been sent ---
						
			// log milliseconds
			long endMsec = System.currentTimeMillis();
			LocalLogger.logToStdErr("FDC query milliseconds: " + String.valueOf(endMsec - startMsec));
			
			// delete temp graph
			tempDataSei.dropGraph();
			
			
		} catch (Exception e) {
			LocalLogger.printStackTrace(e);
			try {
				this.updateStatusToFailed("Internal FDC error " + errorHeader + ": " + e.getMessage());
			} catch (Exception ee) {}
			
		} finally {
			try {
				FdcServiceManager.suggestReCache(this.extConfigSei, this.oInfoClient);
			} catch (Exception e) {
				LocalLogger.printStackTrace(e);
			}
		}
	}
	
	private SparqlEndpointInterface createTempSei() throws Exception {
		SparqlEndpointInterface tempDataSei = querySei.copy();
		tempDataSei.setGraph(querySei.getGraph() + "/FDC_" +  UUID.randomUUID().toString());     // "FDC_TEMP");  PEC TODO test
		tempDataSei.setUserAndPassword(this.tmpGraphUser, this.tmpGraphPassword);
		tempDataSei.createGraph();
		LocalLogger.logToStdOut("Using temp graph: " + tempDataSei.getGraph());
		return tempDataSei;
	}
	
	private SparqlConnection createIngestConn(SparqlEndpointInterface tempDataSei) throws Exception {
		// Build ingest connection: copy of the incoming sgjson's connection
		SparqlConnection ingestConn = SparqlConnection.deepCopy(this.queryNodeGroup.getSparqlConnection());
		ingestConn.setOwlImportsEnabled(true);
		ingestConn.clearDataInterfaces();	
		ingestConn.addDataInterface(tempDataSei);
		return ingestConn;
	}
	
	private SparqlConnection createExpandedConn(SparqlEndpointInterface tempDataSei) throws Exception {
		SparqlConnection expandedConn = SparqlConnection.deepCopy(this.queryNodeGroup.getSparqlConnection());
		expandedConn.addDataInterface(tempDataSei);
		return expandedConn;
	}
	
}
