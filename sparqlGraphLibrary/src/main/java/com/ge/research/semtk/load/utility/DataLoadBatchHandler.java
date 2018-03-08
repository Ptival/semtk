/**
 ** Copyright 2016-2018 General Electric Company
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


package com.ge.research.semtk.load.utility;

import java.util.ArrayList;

import com.ge.research.semtk.belmont.NodeGroup;
import com.ge.research.semtk.load.DataLoader;
import com.ge.research.semtk.load.dataset.Dataset;
import com.ge.research.semtk.load.utility.DataSetExhaustedException;
import com.ge.research.semtk.load.utility.ImportSpecHandler;
import com.ge.research.semtk.ontologyTools.OntologyInfo;
import com.ge.research.semtk.resultSet.Table;
import com.ge.research.semtk.sparqlX.SparqlEndpointInterface;
/*
 * Takes multiple data records and uses them to populate NodeGroups.
 * 
 * WARNING: This is shared by THREADS.  It must remain THREAD SAFE.
 */
public class DataLoadBatchHandler {

	Dataset ds = null;
	int batchSize = 1;
	ImportSpecHandler importSpec = null;
	OntologyInfo oInfo = null;
	Table failuresEncountered = null;
	
	public DataLoadBatchHandler(SparqlGraphJson sgJson, SparqlEndpointInterface endpoint) throws Exception{
		this.oInfo = sgJson.getOntologyInfo();
		this.importSpec = sgJson.getImportSpec();
		this.importSpec.setEndpoint(endpoint);
		
		if (sgJson.getImportSpecJson() == null) {
			throw new Exception("The data transformation import spec is null.");
		}
		if (sgJson.getSNodeGroupJson() == null) {
			throw new Exception("The data transformation nodegroup is null.");
		}
	}

	public DataLoadBatchHandler(SparqlGraphJson sgJson, int batchSize, SparqlEndpointInterface endpoint) throws Exception{
		this(sgJson, endpoint);
		this.batchSize = batchSize;
	}

	public DataLoadBatchHandler(SparqlGraphJson sgJson, int batchSize, Dataset ds, SparqlEndpointInterface endpoint) throws Exception{
		this(sgJson, endpoint);
		this.batchSize = batchSize;
		this.setDataset(ds);
	}
	
	public String [] getImportColNames() {
		return importSpec.getColNamesUsed();
	}
	
	public void setDataset(Dataset ds) throws Exception{
		this.ds = ds;
		if(ds != null){
			this.importSpec.setHeaders(ds.getColumnNamesinOrder());
			
			ArrayList<String> failureCols = this.ds.getColumnNamesinOrder();
			failureCols.add(DataLoader.FAILURE_CAUSE_COLUMN_NAME);
			failureCols.add(DataLoader.FAILURE_RECORD_COLUMN_NAME);
			ArrayList<String> failureColTypes = new ArrayList<String>();

			for(int cntr = 0; cntr < failureCols.size(); cntr++){
				failureColTypes.add("String");
			}
			
			String[] failureColsArray = new String[failureCols.size()];
			failureColsArray = failureCols.toArray(failureColsArray);
			String[] failureColTypesArray = new String[failureColTypes.size()];
			failureColTypesArray = failureColTypes.toArray(failureColTypesArray);

			this.failuresEncountered = new Table(failureColsArray, failureColTypesArray, null);
		}
		else{
			throw new Exception("dataset cannot be null");
		}
	}
	
	public void resetDataSet() throws Exception{
		this.ds.reset();
		this.failuresEncountered.clearRows();
	}
	
	public void setBatchSize(int bSize){
		this.batchSize = bSize;
	}
	
	public ArrayList<ArrayList<String>> getNextRecordsFromDataSet(int nRecordsRequested) throws Exception{
		 return this.ds.getNextRecords(nRecordsRequested);
	}

	public ArrayList<ArrayList<String>> getNextRecordsFromDataSet() throws Exception{
		 return this.ds.getNextRecords(this.batchSize);
	}
	
	public ArrayList<NodeGroup> getNextNodeGroups(int nRecordsRequested, int startingRowNum, boolean skipValidation) throws Exception {
		
		ArrayList<ArrayList<String>> recordList = this.ds.getNextRecords(nRecordsRequested);
		
		if(recordList.size() == 0){
			// no new records
			throw new DataSetExhaustedException("No more records to read");
		}
		
		return this.convertToNodeGroups(recordList, startingRowNum, skipValidation);
		
	}

	/**
	 * Creates one nodegroup per successfully converted record.   SUCCESS: if ret.size() == recordList.size()
	 * Also writes to failuresEncountered if failures occur.
	 * 
	 * NOTE: this method in particular needs to be THREAD SAFE
	 * 
	 * @param recordList
	 * @param skipValidation
	 * @return nodegroup for each successfully converted record.   
	 * @throws Exception - only on serious internal error
	 */
	public ArrayList<NodeGroup> convertToNodeGroups(ArrayList<ArrayList<String>> recordList, int startingRowNum, boolean skipValidation) throws Exception {
		// take the response we received and build the result node groups we care about.
		ArrayList<NodeGroup> retval = new ArrayList<NodeGroup>();
		
		// if cannot read more records (e.g. if all records have been read already), nothing to do
		if(recordList == null){
			return retval;
		}
		
		for (int i=0; i < recordList.size(); i++) {
			ArrayList<String> record = recordList.get(i);
			
			// get our new node group
			NodeGroup cng = null;
			
			// add the values from the results to it.
			try{
				cng = this.importSpec.buildImportNodegroup(record, skipValidation);
			
				// add the new group to the output arraylist, only if it succceeded
				retval.add(cng);
			}
			catch(Exception e){
				// some variety of failure occured.
				ArrayList<String> newErrorReport = new ArrayList<String>();
				// add default columns
				for(String currCol : record){
					newErrorReport.add(currCol);
				}
				// add error report columns
				if (e instanceof RuntimeException) {
					newErrorReport.add(e.toString());
				} else {
					newErrorReport.add(e.getMessage());
				}
				newErrorReport.add(String.valueOf(startingRowNum + i));
				this.addFailureRow(newErrorReport);
			}
		}
			
		return retval;
	}

	/**
	 * Append to the failureEncountered Table in a thread-safe manner
	 * @param newErrorReport
	 * @throws Exception
	 */
	private synchronized void addFailureRow(ArrayList<String> newErrorReport) throws Exception {
		this.failuresEncountered.addRow(newErrorReport);
	}
	
	public void closeDataSet() throws Exception {
		this.ds.close();
	}
	
	public Table getErrorReport(){
		this.failuresEncountered.sortByColumnInt(DataLoader.FAILURE_RECORD_COLUMN_NAME);
		return this.failuresEncountered;
	}

	public int getBatchSize() {
		return this.batchSize;
	}
	
	/**
	 * After a possibly threaded pass where lookupURI labeled some URI's as NOT_FOUND
	 * Now go through and generate GUIDs for them.
	 * @throws Exception
	 */
	public void generateNotFoundURIs() throws Exception {
		this.importSpec.generateNotFoundURIs();
	}
	
	/**
	 * Does LOOKUP_MODE_CREATE appear anywhere in the ImportSpec
	 * @return
	 */
	public boolean containsLookupModeCreate() {
		return this.importSpec.containsLookupModeCreate();
	}
}
