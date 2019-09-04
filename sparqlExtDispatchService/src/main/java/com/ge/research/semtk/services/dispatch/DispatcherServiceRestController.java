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

package com.ge.research.semtk.services.dispatch;

import java.lang.reflect.Constructor;
import java.util.UUID;

import javax.annotation.PostConstruct;

import org.json.simple.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.ge.research.semtk.api.nodeGroupExecution.client.NodeGroupExecutionClient;
import com.ge.research.semtk.api.nodeGroupExecution.client.NodeGroupExecutionClientConfig;
import com.ge.research.semtk.auth.AuthorizationManager;
import com.ge.research.semtk.auth.ThreadAuthenticator;
import com.ge.research.semtk.edc.client.OntologyInfoClient;
import com.ge.research.semtk.edc.client.OntologyInfoClientConfig;
import com.ge.research.semtk.edc.client.ResultsClient;
import com.ge.research.semtk.edc.client.ResultsClientConfig;
import com.ge.research.semtk.edc.client.StatusClient;
import com.ge.research.semtk.edc.client.StatusClientConfig;
import com.ge.research.semtk.load.utility.SparqlGraphJson;
import com.ge.research.semtk.utility.LocalLogger;
import com.ge.research.semtk.resultSet.SimpleResultSet;
import com.ge.research.semtk.services.dispatch.DispatchProperties;
import com.ge.research.semtk.services.dispatch.NodegroupRequestBody;
import com.ge.research.semtk.services.dispatch.WorkThread;
import com.ge.research.semtk.sparqlX.BadQueryException;
import com.ge.research.semtk.sparqlX.client.SparqlQueryAuthClientConfig;
import com.ge.research.semtk.sparqlX.client.SparqlQueryClient;
import com.ge.research.semtk.sparqlX.client.SparqlQueryClientConfig;
import com.ge.research.semtk.springutillib.headers.HeadersManager;
import com.ge.research.semtk.springutillib.properties.EnvironmentProperties;
import com.ge.research.semtk.sparqlX.asynchronousQuery.AsynchronousNodeGroupBasedQueryDispatcher;
import com.ge.research.semtk.sparqlX.asynchronousQuery.DispatcherSupportedQueryTypes;

@RestController
@RequestMapping("/dispatcher")
public class DispatcherServiceRestController {
 	static final String SERVICE_NAME = "dispatcher";
 	
 	// old fashioned
	@Autowired
	DispatchProperties props;
	
	// new fangled
	@Autowired
	DispatcherNGEServiceProperties nge_props;
	
	@PostConstruct
    public void init() {
		nge_props.validateWithExit();
		
		// ---- still in old-fashioned DispatcherServiceStartup  ----
		
		//EnvironmentProperties env_prop = new EnvironmentProperties(appContext, EnvironmentProperties.SEMTK_REQ_PROPS, EnvironmentProperties.SEMTK_OPT_PROPS);
		//env_prop.validateWithExit();
		
		//auth_prop.validateWithExit();
		//AuthorizationManager.authorizeWithExit(auth_prop);

	}
	
	// select uses the original endpoint name for BC
	@CrossOrigin
	@RequestMapping(value="/queryFromNodeGroup", method=RequestMethod.POST)
	public JSONObject querySelectFromNodeGroup_BC(@RequestBody QueryRequestBody requestBody, @RequestHeader HttpHeaders headers) {
		HeadersManager.setHeaders(headers);
		try {
			return queryFromNodeGroup(requestBody, DispatcherSupportedQueryTypes.SELECT_DISTINCT, true);
		    
		} finally {
	    	HeadersManager.clearHeaders();
	    }
	}
	
	@CrossOrigin
	@RequestMapping(value="/querySelectFromNodeGroup", method=RequestMethod.POST)
	public JSONObject querySelectFromNodeGroup(@RequestBody QueryRequestBody requestBody, @RequestHeader HttpHeaders headers) {
		HeadersManager.setHeaders(headers);
		try {
			return queryFromNodeGroup(requestBody, DispatcherSupportedQueryTypes.SELECT_DISTINCT, true);
		    
		} finally {
	    	HeadersManager.clearHeaders();
	    }
	}

	@CrossOrigin
	@RequestMapping(value="/queryCountFromNodeGroup", method=RequestMethod.POST)
	public JSONObject queryCounttFromNodeGroup(@RequestBody QueryRequestBody requestBody, @RequestHeader HttpHeaders headers) {
		HeadersManager.setHeaders(headers);
		try {
			return queryFromNodeGroup(requestBody, DispatcherSupportedQueryTypes.COUNT, true);
		    
		} finally {
	    	HeadersManager.clearHeaders();
	    }
	}
	
	@CrossOrigin
	@RequestMapping(value="/queryDeleteFromNodeGroup", method=RequestMethod.POST)
	public JSONObject queryDeleteFromNodeGroup(@RequestBody QueryRequestBody requestBody, @RequestHeader HttpHeaders headers) {
		HeadersManager.setHeaders(headers);
		try {
			return queryFromNodeGroup(requestBody, DispatcherSupportedQueryTypes.DELETE, true);
		    
		} finally {
	    	HeadersManager.clearHeaders();
	    }
	}

	@CrossOrigin
	@RequestMapping(value="/queryFilterFromNodeGroup", method=RequestMethod.POST)
	public JSONObject queryFilterFromNodeGroup(@RequestBody FilterConstraintsRequestBody requestBody, @RequestHeader HttpHeaders headers) {
		HeadersManager.setHeaders(headers);
		try {
			return queryFromNodeGroup(requestBody, DispatcherSupportedQueryTypes.FILTERCONSTRAINT, true);
		    
		} finally {
	    	HeadersManager.clearHeaders();
	    }
	}

	@CrossOrigin
	@RequestMapping(value="/asynchronousDirectQuery", method=RequestMethod.POST)
	public JSONObject asynchronousDirectQuery(@RequestBody SparqlRequestBody requestBody, @RequestHeader HttpHeaders headers) {
		HeadersManager.setHeaders(headers);
		try {
			return queryFromSparql(requestBody, DispatcherSupportedQueryTypes.RAW_SPARQL);
		    
		} finally {
	    	HeadersManager.clearHeaders();
	    }
	}
	
	@CrossOrigin
	@RequestMapping(value="/asynchronousDirectUpdateQuery", method=RequestMethod.POST)
	public JSONObject asynchronousDirectUpdateQuery(@RequestBody SparqlRequestBody requestBody, @RequestHeader HttpHeaders headers) {
		HeadersManager.setHeaders(headers);
		try {
			return queryFromSparql(requestBody, DispatcherSupportedQueryTypes.RAW_SPARQL_UPDATE);
		    
		} finally {
	    	HeadersManager.clearHeaders();
	    }
	}
	
	@CrossOrigin
	@RequestMapping(value="/queryConstructFromNodeGroup", method=RequestMethod.POST)
	public JSONObject queryConstructFromNodeGroup(@RequestBody QueryRequestBody requestBody, @RequestHeader HttpHeaders headers) {
		HeadersManager.setHeaders(headers);
		try {
			return queryFromNodeGroup(requestBody, DispatcherSupportedQueryTypes.CONSTRUCT, true);
		    
		} finally {
	    	HeadersManager.clearHeaders();
	    }
	}
	
	@CrossOrigin
	@RequestMapping(value="/queryConstructFromNodeGroupForInstanceManipulation", method=RequestMethod.POST)
	public JSONObject queryConstructFromNodeGroupForInstanceManipulation(@RequestBody QueryRequestBody requestBody, @RequestHeader HttpHeaders headers) {
		HeadersManager.setHeaders(headers);
		try {
			return queryFromNodeGroup(requestBody, DispatcherSupportedQueryTypes.CONSTRUCT_FOR_INSTANCE_DATA_MANIPULATION, true);
		    
		} finally {
	    	HeadersManager.clearHeaders();
	    }
	}
		
	public JSONObject queryFromSparql(@RequestBody SparqlRequestBody requestBody, DispatcherSupportedQueryTypes qt){
		String requestId = this.generateJobId();
		SimpleResultSet retval = new SimpleResultSet(true);
		retval.addResult("requestID", requestId);
		
		AsynchronousNodeGroupBasedQueryDispatcher dsp = null;
		
		// create a request ID and set the value.
		
		// get the things we need for the dispatcher
		try {
			SparqlGraphJson sgjson = new SparqlGraphJson();
			sgjson.setSparqlConn( requestBody.getConnection());
			
			NodegroupRequestBody ngrb = new NodegroupRequestBody();
			ngrb.setjsonRenderedNodeGroup(sgjson.getJson().toJSONString());
			
			dsp = getDispatcher(props, requestId, ngrb, true, true);
			dsp.getStatusClient().execIncrementPercentComplete(1, 10);
			
			WorkThread thread = new WorkThread(dsp, null, null, qt);

			if(qt.equals(DispatcherSupportedQueryTypes.RAW_SPARQL) || qt.equals(DispatcherSupportedQueryTypes.RAW_SPARQL_UPDATE)) {
				// we are going to launch straight from the raw sparql
				String qry = ((SparqlRequestBody)requestBody).getRawSparqlQuery();
				thread.setRawSparqlSquery(qry);
			}
			
			// set up a thread for the actual processing of the request
			thread.start();
			 
		} catch (Exception e) {
			LocalLogger.printStackTrace(e);

			retval.setSuccess(false);
			retval.addRationaleMessage(SERVICE_NAME, "../queryFromSparql()", e);
			
			// claim a failure?
			StatusClient sClient = null;
			try {
				sClient = new StatusClient(new StatusClientConfig(props.getStatusServiceProtocol(), props.getStatusServiceServer(), props.getStatusServicePort(), requestId));
			} catch (Exception e2) {
				LocalLogger.printStackTrace(e2);
			}
			if(sClient != null){ 
				try {
					sClient.execSetFailure(e.getMessage());
				} catch (Exception e1) {
					LocalLogger.printStackTrace(e1);
				}
			}
			
		} 
		// send back the request ID.
		// the request is not finished but that is okay
		return retval.toJson();
	}
	
	/**
	 * 
	 * @param requestBody
	 * @param qt
	 * @param useAuth    NOTE - set to true for performance.  Non-auth queries containing FROM clauses are very slow when using the non-auth endpoint.
	 * @return
	 */
	public JSONObject queryFromNodeGroup(@RequestBody QueryRequestBody requestBody, DispatcherSupportedQueryTypes qt, Boolean useAuth){
		String requestId = this.generateJobId();
		SimpleResultSet retval = new SimpleResultSet(true);
		retval.addResult("requestID", requestId);
		
		AsynchronousNodeGroupBasedQueryDispatcher dsp = null;
		
		// create a request ID and set the value.
		
		// get the things we need for the dispatcher
		try {
			dsp = getDispatcher(props, requestId, (NodegroupRequestBody) requestBody, useAuth, true);
			dsp.getStatusClient().execIncrementPercentComplete(1, 10);

			WorkThread thread = new WorkThread(dsp, requestBody.getExternalConstraints(), requestBody.getFlags(), qt);
			
			if(qt.equals(DispatcherSupportedQueryTypes.FILTERCONSTRAINT)){
				// we should have a potential target object.				
				String target = ((FilterConstraintsRequestBody)requestBody).getTargetObjectSparqlID();
				thread.setTargetObjectSparqlID(target);
			}
		
			// set up a thread for the actual processing of the request
			thread.start();
			 
		} catch (Exception e) {
			LocalLogger.printStackTrace(e);
			retval.setSuccess(false);
			retval.addRationaleMessage(SERVICE_NAME, "../queryFromNodegroup()", e);
			
			// claim a failure?
			StatusClient sClient = null;
			try {
				sClient = new StatusClient(new StatusClientConfig(props.getStatusServiceProtocol(), props.getStatusServiceServer(), props.getStatusServicePort(), requestId));
			} catch (Exception e2) {
				LocalLogger.printStackTrace(e2);
			}
			if(sClient != null){ 
				try {
					sClient.execSetFailure(e.getMessage());
				} catch (Exception e1) {
					LocalLogger.printStackTrace(e1);
				}
			}
			
		} 
		// send back the request ID.
		// the request is not finished but that is okay
		return retval.toJson();
	}

	@CrossOrigin
	@RequestMapping(value="/getConstraintInfo", method=RequestMethod.POST)
	public JSONObject getConstraintInfo(@RequestBody NodegroupRequestBody requestBody, @RequestHeader HttpHeaders headers) {
		HeadersManager.setHeaders(headers);
		try {
			SimpleResultSet retval = new SimpleResultSet(true);
			
			AsynchronousNodeGroupBasedQueryDispatcher dsp = null;
			String fakeReqId = "unused_in_this_case";
			// get the things we need for the dispatcher
			try {
				
				dsp = getDispatcher(props, fakeReqId, (NodegroupRequestBody) requestBody, true, false);
				
				retval.addResult("constraintType", dsp.getConstraintType());
				retval.addResultStringArray("variableNames", dsp.getConstraintVariableNames());
				 
			} catch (BadQueryException bqe) {
				// handle this exception by showing the user the simplified message.
				retval.setSuccess(false);
				retval.addRationaleMessage(bqe.getMessage());
			}
			catch (Exception e) {
				LocalLogger.printStackTrace(e);
				retval.setSuccess(false);
				retval.addRationaleMessage(SERVICE_NAME, "getConstraintInfo", e);
			} 
			// send back the request ID.
			// the request is not finished but that is okay
			return retval.toJson();
		    
		} finally {
	    	HeadersManager.clearHeaders();
	    }
	}
	
	private String generateJobId(){
		return "req_" + UUID.randomUUID();
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private AsynchronousNodeGroupBasedQueryDispatcher getDispatcher(DispatchProperties prop, String requestId, NodegroupRequestBody requestBody, Boolean useAuth, Boolean heedRestrictions) throws Exception{
		
		// get the sgJson
		SparqlGraphJson sgJson = null;		
		try{
			sgJson = new SparqlGraphJson(requestBody.getJsonNodeGroup()) ;
		}catch(Exception sg){
			throw new Exception("Dispatcher cannot get sparqlgraphJson from request: " + sg.getMessage());
		}

		// get clients needed to instantiate the Dispatcher
		SparqlQueryClientConfig queryConf = null;
		SparqlQueryClient servicesQueryClient = null;
		if(useAuth){
			queryConf = new SparqlQueryAuthClientConfig(	
					props.getSparqlServiceProtocol(),
					props.getSparqlServiceServer(), 
					props.getSparqlServicePort(), 
					props.getSparqlServiceAuthEndpoint(),
	                props.getEdcSparqlServerAndPort(), 
	                props.getEdcSparqlServerType(), 
	                props.getEdcSparqlServerDataset(),
					props.getSparqlServiceUser(),
					props.getSparqlServicePass());
			servicesQueryClient = new SparqlQueryClient(queryConf);
			
		}
		else{
			queryConf = new SparqlQueryClientConfig(	
					props.getSparqlServiceProtocol(),
					props.getSparqlServiceServer(), 
					props.getSparqlServicePort(), 
					props.getSparqlServiceEndpoint(),
	                props.getEdcSparqlServerAndPort(), 
	                props.getEdcSparqlServerType(), 
	                props.getEdcSparqlServerDataset());
			servicesQueryClient = new SparqlQueryClient(queryConf);
		}		
		
		ResultsClient rClient = new ResultsClient(new ResultsClientConfig(props.getResultsServiceProtocol(), props.getResultsServiceServer(), props.getResultsServicePort()));
		StatusClient sClient = new StatusClient(new StatusClientConfig(props.getStatusServiceProtocol(), props.getStatusServiceServer(), props.getStatusServicePort(), requestId));
		OntologyInfoClient oClient = new OntologyInfoClient(new OntologyInfoClientConfig(props.getOinfoServiceProtocol(), props.getOinfoServiceServer(), props.getOinfoServicePort()));
		NodeGroupExecutionClient ngeClient = new NodeGroupExecutionClient(new NodeGroupExecutionClientConfig(nge_props.getProtocol(), nge_props.getServer(), nge_props.getPort(), props.getSparqlServiceUser(), props.getSparqlServicePass()));

		sClient.execSetPercentComplete(0, "Job Initialized");
		
		// instantiate the dispatcher from the class name 
		AsynchronousNodeGroupBasedQueryDispatcher dsp = null;
		try{
			LocalLogger.logToStdOut("Dispatcher class: " + props.getDispatcherClassName() );
			Class<?> dspClass = Class.forName(props.getDispatcherClassName());			
			if(dspClass == null) { 
				throw new Exception("Dispatcher class is null");
			}
			LocalLogger.logToStdOut("Dispatcher class name is " + dspClass.getCanonicalName());

			Constructor ctor = null ; 	
			for (Constructor c : dspClass.getConstructors() ){
				Class[] params = c.getParameterTypes();
				// this is not a great way to get the constructor but the more traditional single call was failing pretty badly.
				if(params[0].isAssignableFrom( String.class )) {
					if(params[1].isAssignableFrom( SparqlGraphJson.class )) {
						if(params[2].isAssignableFrom( ResultsClient.class )){
							if( params[3].isAssignableFrom( StatusClient.class )){
								if(params[4].isAssignableFrom( SparqlQueryClient.class )){
									ctor = c;
								}}}}
				}
			}
			
			dsp = (AsynchronousNodeGroupBasedQueryDispatcher) ctor.newInstance(requestId, sgJson, rClient, sClient, servicesQueryClient, heedRestrictions, oClient, ngeClient);
			
		}catch(Exception e){
			// log entire stack trace
			LocalLogger.printStackTrace(e);
			
			// find and throw only the causing exception
			Throwable t = e;
			while (t.getCause() != null) {
				t = t.getCause();
			}
			throw (Exception) t;
		}
		
		LocalLogger.logToStdOut("initialized job: " + requestId);	
		return dsp;
	}

}
