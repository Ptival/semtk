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

package com.ge.research.semtk.services.nodegroupStore.service;

import java.util.ArrayList;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.ge.research.semtk.sparqlX.client.SparqlQueryAuthClientConfig;
import com.ge.research.semtk.sparqlX.client.SparqlQueryClient;
import com.ge.research.semtk.sparqlX.client.SparqlQueryClientConfig;
import com.ge.research.semtk.springutilib.requests.IdRequest;
import com.ge.research.semtk.springutillib.headers.HeadersManager;
import com.ge.research.semtk.belmont.NodeGroup;
import com.ge.research.semtk.load.utility.SparqlGraphJson;
import com.ge.research.semtk.utility.LocalLogger;
import com.ge.research.semtk.resultSet.GeneralResultSet;
import com.ge.research.semtk.resultSet.SimpleResultSet;
import com.ge.research.semtk.resultSet.Table;
import com.ge.research.semtk.resultSet.TableResultSet;
import com.ge.research.semtk.services.nodegroupStore.NgStoreSparqlGenerator;
import com.ge.research.semtk.services.nodegroupStore.StoreNodeGroup;

import com.ge.research.semtk.sparqlX.SparqlConnection;
import com.ge.research.semtk.sparqlX.SparqlResultTypes;

@RestController
@RequestMapping("/nodeGroupStore")
@CrossOrigin
public class NodeGroupStoreRestController {

	
	@CrossOrigin
	@RequestMapping(value= "/**", method=RequestMethod.OPTIONS)
	public void corsHeaders(HttpServletResponse response) {
	    response.addHeader("Access-Control-Allow-Origin", "*");
	    response.addHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
	    response.addHeader("Access-Control-Allow-Headers", "origin, content-type, accept, x-requested-with");
	    response.addHeader("Access-Control-Max-Age", "3600");
	}
	
	@Autowired
	StoreProperties prop;
	
	private static final String SERVICE_NAME="nodeGroupStore";
	
	/**
	 * Store a new nodegroup to the nodegroup store.
	 * @param requestBody
	 * @return
	 */
	@CrossOrigin
	@RequestMapping(value="/storeNodeGroup", method=RequestMethod.POST)
	public JSONObject storeNodeGroup(@RequestBody StoreNodeGroupRequest requestBody, @RequestHeader HttpHeaders headers) {
		HeadersManager.setHeaders(headers);
		try {
			final String SVC_ENDPOINT_NAME = SERVICE_NAME + "/storeNodeGroup";
			SimpleResultSet retval = null;
	
			try{
	
				// throw a meaningful exception if needed info is not present in the request
				requestBody.validate();	
	
				// check that the ID does not already exist. if it does, fail.
				NgStoreSparqlGenerator sparqlGen = new NgStoreSparqlGenerator(prop.getSparqlConnDataDataset());
				ArrayList<String> queries = sparqlGen.getNodeGroupByID(requestBody.getName());
				SparqlQueryClient clnt = createClient(prop);
	
				TableResultSet instanceTable = (TableResultSet) clnt.execute(queries.get(0), SparqlResultTypes.TABLE);
	
				if(instanceTable.getTable().getNumRows() > 0){
					throw new Exception("Unable to store node group:  ID (" + requestBody.getName() + ") already exists");
				}
	
				// get the nodeGroup and the connection info:
				JSONObject sgJsonJson = requestBody.getJsonNodeGroup();			// changed to allow for more dynamic nodegroup actions. 
				SparqlGraphJson sgJson = new SparqlGraphJson(sgJsonJson);
				// the next line was removed to make sure the node group is not "stripped" -- cut down to just the nodegroup proper when stored. 
				// the executor is smart enough to deal with both cases. 
	
	
				JSONObject connJson = sgJson.getSparqlConnJson();
	
				if(connJson == null){
					// we really should not continue if we are not sure where this came from originally. 
					// throw an error and fail gracefully... ish.
					throw new Exception("storeNodeGroup :: sparqlgraph jason serialization passed to store node group did not contain a valid connection block. it is possible that only the node group itself was passed. please check that complete input is sent.");
				}
	
				queries = sparqlGen.insertNodeGroup(sgJsonJson, connJson, requestBody.getName(), requestBody.getComments(), requestBody.getCreator());
				
				for (String query : queries) {
					GeneralResultSet gRes = clnt.execute(query, SparqlResultTypes.CONFIRM);
						
					if (!gRes.getSuccess()) {
						throw new Exception(gRes.getRationaleAsString(" "));
					}
				}
				
				retval = new SimpleResultSet(true);
	
			}
			catch(Exception e){
				retval = new SimpleResultSet(false);
				retval.addRationaleMessage(SVC_ENDPOINT_NAME, e);
				LocalLogger.printStackTrace(e);
			} 	
			return retval.toJson();
		    
		} finally {
	    	HeadersManager.clearHeaders();
	    }
	}

	@CrossOrigin
	@RequestMapping(value="/getNodeGroupById", method=RequestMethod.POST)
	public JSONObject getNodeGroupById(@RequestBody @Valid IdRequest requestBody, @RequestHeader HttpHeaders headers) {
		HeadersManager.setHeaders(headers);
		try {
			final String SVC_ENDPOINT_NAME = SERVICE_NAME + "/getNodeGroupById";
			TableResultSet retval = null;
	
			try {
				retval = this.getNodeGroupById(requestBody.getId(), prop.getSparqlConnDataDataset());
			}
			catch (Exception e) {
				// something went wrong. report and exit. 
	
				retval = new TableResultSet(false);
				retval.addRationaleMessage(SVC_ENDPOINT_NAME, e);
			} 
	
			return retval.toJson();  // whatever we have... send it out. 
		    
		} finally {
	    	HeadersManager.clearHeaders();
	    }
	}

	@CrossOrigin
	@RequestMapping(value="/getNodeGroupList", method=RequestMethod.POST)
	public JSONObject getNodeGroupList(@RequestHeader HttpHeaders headers) {
		HeadersManager.setHeaders(headers);
		try {
			final String SVC_ENDPOINT_NAME = SERVICE_NAME + "/getNodeGroupList";
			TableResultSet retval = null;
	
			try{
				NgStoreSparqlGenerator sparqlGen = new NgStoreSparqlGenerator(prop.getSparqlConnDataDataset());
				String qry = sparqlGen.getFullNodeGroupList();
				SparqlQueryClient clnt = createClient(prop);
	
				retval = (TableResultSet) clnt.execute(qry, SparqlResultTypes.TABLE);
			}
			catch(Exception e){
				// something went wrong. report and exit. 
				retval = new TableResultSet(false);
				retval.addRationaleMessage(SVC_ENDPOINT_NAME, e);
			} 
	
			return retval.toJson();  // whatever we have... send it out. 
		    
		} finally {
	    	HeadersManager.clearHeaders();
	    }
	}

	@CrossOrigin
	@RequestMapping(value="/getNodeGroupMetadata", method=RequestMethod.POST)
	public JSONObject getNodeGroupMetadata(@RequestHeader HttpHeaders headers) {
		HeadersManager.setHeaders(headers);
		try {
			final String SVC_ENDPOINT_NAME = SERVICE_NAME + "/getNodeGroupMetadata";
			TableResultSet retval = null;		
			try{
				NgStoreSparqlGenerator sparqlGen = new NgStoreSparqlGenerator(prop.getSparqlConnDataDataset());
				String qry = sparqlGen.getNodeGroupMetadata();
				SparqlQueryClient clnt = createClient(prop);
				retval = (TableResultSet) clnt.execute(qry, SparqlResultTypes.TABLE);
			}
			catch(Exception e){
				// something went wrong. report and exit. 
				retval = new TableResultSet(false);
				retval.addRationaleMessage(SVC_ENDPOINT_NAME, e);
			} 
			return retval.toJson();   
		    
		} finally {
	    	HeadersManager.clearHeaders();
	    }
	}

	@CrossOrigin
	@RequestMapping(value="/getNodeGroupRuntimeConstraints", method=RequestMethod.POST)
	public JSONObject getRuntimeConstraints(@RequestBody @Valid IdRequest requestBody, @RequestHeader HttpHeaders headers) {
		HeadersManager.setHeaders(headers);
		try {
			final String SVC_ENDPOINT_NAME = SERVICE_NAME + "/getNodeGroupRuntimeConstraints";
			TableResultSet retval = null;
	
			try{
				TableResultSet temp = this.getNodeGroupById(requestBody.getId(), prop.getSparqlConnDataDataset());
	
				// get the first result and return all the runtime constraints for it.
				Table tbl = temp.getResults();
	
				if(tbl.getNumRows() > 0){
					// we have a result. for now, let's assume that only the first result is valid.
					ArrayList<String> tmpRow = tbl.getRows().get(0);
					int targetCol = tbl.getColumnIndex("NodeGroup");
	
					String ngJSONstr = tmpRow.get(targetCol);
					JSONParser jParse = new JSONParser();
					JSONObject json = (JSONObject) jParse.parse(ngJSONstr); 
	
					// check if this is a wrapped or unwrapped 
					// check that sNodeGroup is a key in the json. if so, this has a connection and the rest.
					if (SparqlGraphJson.isSparqlGraphJson(json)) {
						SparqlGraphJson sgJson = new SparqlGraphJson(json);
						LocalLogger.logToStdErr("located key: sNodeGroup");
						json = sgJson.getSNodeGroupJson();
					}
	
					// otherwise, check for a truncated one that is only the nodegroup proper.
					else if(! NodeGroup.isNodeGroup(json)) {
	
						throw new Exception("Value given for encoded node group can't be parsed");
					}
	
	
					// get the runtime constraints. 
	
					retval = new TableResultSet(true); 
					retval.addResults(StoreNodeGroup.getConstrainedItems(json));
				} else {
					retval = new TableResultSet(false);
					retval.addRationaleMessage(SVC_ENDPOINT_NAME, "Nodegroup was not found: " + requestBody.getId());
				}
	
			}
			catch(Exception e){
				// something went wrong. report and exit. 
	
				LocalLogger.logToStdErr("a failure was encountered during the retrieval of runtime constraints: " + 
						e.getMessage());
	
				retval = new TableResultSet(false);
				retval.addRationaleMessage(SVC_ENDPOINT_NAME, "Nodegroup was not found: " + requestBody.getId());
			}
			return retval.toJson();
		    
		} finally {
	    	HeadersManager.clearHeaders();
	    }
	}
	/**
	 * This method uses a static delete query. it would be better to use a local nodegroup and have belmont 
	 * generate a deletion query itself. 
	 * @param requestBody
	 * @return
	 */
	@CrossOrigin
	@RequestMapping(value="/deleteStoredNodeGroup", method=RequestMethod.POST)
	public JSONObject deleteStoredNodeGroup(@RequestBody @Valid IdRequest requestBody, @RequestHeader HttpHeaders headers) {
		HeadersManager.setHeaders(headers);
		try {
			SimpleResultSet retval = null;
	
			// NOTE:
			// this static delete works but will need to be updated should the metadata surrounding node groups be updated. 
			// really, if the insertion nodegroup itself is edited, this should be looked at.
			// ideally, the node groups would be able to write deletion queries, using filters and runtime constraints to
			// determine what to remove. if we moved to that point, we could probably use the same NG for insertions and deletions.
	
			NgStoreSparqlGenerator sparqlGen = new NgStoreSparqlGenerator(prop.getSparqlConnDataDataset());
			String qry = sparqlGen.deleteNodeGroup(requestBody.getId());
					
	
			try{
				// attempt to delete the nodegroup, name and comments where there is a give ID.
				SparqlQueryClient clnt = createClient(prop);
				retval = (SimpleResultSet) clnt.execute(qry, SparqlResultTypes.CONFIRM);
	
	
	
			}
			catch(Exception e){
				// something went wrong. report and exit. 
	
				LocalLogger.logToStdErr("a failure was encountered during the deletion of " +  requestBody.getId() + ": " + 
						e.getMessage());
	
				retval = new SimpleResultSet(false);
				retval.addRationaleMessage(e.getMessage());
			} 	
	
			return retval.toJson();
		    
		} finally {
	    	HeadersManager.clearHeaders();
	    }
	}

	/**
	 * Generate multiple quries, exec, stitch together to retrieve a nodegroup
	 * @param id
	 * @return
	 * @throws Exception
	 */
	private TableResultSet getNodeGroupById(String id, String storeDataGraph) throws Exception {
	
		NgStoreSparqlGenerator sparqlGen = new NgStoreSparqlGenerator(storeDataGraph);
		ArrayList<String> queries = sparqlGen.getNodeGroupByID(id);
		SparqlQueryClient clnt = createClient(prop);

		TableResultSet retval = (TableResultSet) clnt.execute(queries.get(0), SparqlResultTypes.TABLE);
		
		// look for additional text
		TableResultSet catval = (TableResultSet) clnt.execute(queries.get(1), SparqlResultTypes.TABLE);
		catval.throwExceptionIfUnsuccessful();
		if (catval.getTable().getNumRows() > 0) {
			StringBuilder ngStr = new StringBuilder(retval.getTable().getCellAsString(0,  "NodeGroup"));
			
			// append additional text
			for (int i=0; i < catval.getTable().getNumRows(); i++) {
				ngStr.append(catval.getTable().getCellAsString(i, "NodeGroup"));
			}
			
			// overwrite table in original retval
			int col = retval.getTable().getColumnIndex("NodeGroup");
			Table fullTable = retval.getTable();
			fullTable.setCell(0,  col, ngStr.toString());
			retval.addResults(fullTable);
		}
		return retval;
	}
	
	// static method to avoid repeating the client generation code...


	private static SparqlQueryClient createClient(StoreProperties props) throws Exception{

		SparqlQueryClient retval = new SparqlQueryClient((SparqlQueryClientConfig)(new SparqlQueryAuthClientConfig(	
				props.getSparqlServiceProtocol(),
				props.getSparqlServiceServer(), 
				props.getSparqlServicePort(), 
				props.getSparqlServiceEndpoint(),
				props.getSparqlConnServerAndPort(), 
				props.getSparqlConnType(), 
				props.getSparqlConnDataDataset(),
				props.getSparqlServiceUser(),
				props.getSparqlServicePass())
				));

		return retval;
	}

	private static SparqlConnection createOverrideConnection(StoreProperties props) throws Exception {
		SparqlConnection retval = new SparqlConnection();
		retval.setName("store override");
		retval.setDomain(props.getSparqlConnDomain());
		retval.addDataInterface(props.getSparqlConnType(), props.getSparqlConnServerAndPort(), props.getSparqlConnDataDataset());
		retval.addModelInterface(props.getSparqlConnType(), props.getSparqlConnServerAndPort(), props.getSparqlConnModelDataset());

		return retval;
	}

}
