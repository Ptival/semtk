/**
 ** Copyright 2017 General Electric Company
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


package com.ge.research.semtk.services.ontologyinfo;

import java.net.URL;

import org.json.simple.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.ge.research.semtk.logging.easyLogger.LoggerRestClient;
import com.ge.research.semtk.ontologyTools.OntologyInfo;
import com.ge.research.semtk.resultSet.SimpleResultSet;
import com.ge.research.semtk.services.ontologyinfo.OntologyInfoLoggingProperties;
import com.ge.research.semtk.sparqlX.SparqlConnection;
import com.ge.research.semtk.sparqlX.SparqlEndpointInterface;

@CrossOrigin
@RestController
@RequestMapping("/ontologyinfo")
public class OntologyInfoServiceRestController {

	@Autowired
	OntologyInfoLoggingProperties log_prop;
	
	@Autowired
	private OntologyServiceProperties service_prop;

	
	@CrossOrigin
	@RequestMapping(value="/getVisJs", method= RequestMethod.POST)
	public JSONObject getVisJs(@RequestBody OntologyInfoRequestBody requestBody){
		
		LoggerRestClient logger = LoggerRestClient.loggerConfigInitialization(log_prop);
	    LoggerRestClient.easyLog(logger, "OntologyInfoService", "getVisJs start");
    	logToStdout("OntologyInfo Service getVisJs start");
    	
    	SimpleResultSet res = new SimpleResultSet();
        
    	String serverType = "";
    	String serverUrl = ""; 
    	
	    try {
	    	
	    	if(requestBody.serverType == null || requestBody.serverType.isEmpty()) {
	    		serverType = service_prop.getServerType(); 
	    	} else {
	    		serverType = requestBody.serverType;
	    	}
	    	
	    	if(requestBody.serverAndPort == null || requestBody.serverAndPort.isEmpty()) {
	    		serverUrl = service_prop.getServerURL(); 
	    	} else {
	    		serverUrl = requestBody.serverAndPort; 
	    	}
	    	
	    	SparqlConnection conn = new SparqlConnection();
	    	conn.setDomain(requestBody.domain);
	    	conn.addModelInterface(serverType, serverUrl, requestBody.dataset);
	    	OntologyInfo oInfo = new OntologyInfo(conn);
	    	
	    	res.addResultsJSON(oInfo.toVisJs());
	    	res.setSuccess(true);
	    	
	    } catch (Exception e) {
	    	res.setSuccess(false);
	    	res.addRationaleMessage(e.toString());
		    LoggerRestClient.easyLog(logger, "OntologyInfoService", "getVisJs exception", "message", e.toString());
		    e.printStackTrace();
	    }
	    
	    return res.toJson();
	}

	@CrossOrigin
	@RequestMapping(value="/getDetailedOntologyInfo", method= RequestMethod.POST)
	public JSONObject getVisJs(@RequestBody DetailedOntologyInfoRequestBody requestBody){
		
		LoggerRestClient logger = LoggerRestClient.loggerConfigInitialization(log_prop);
	    LoggerRestClient.easyLog(logger, "OntologyInfoService", "getVisJs start");
    	logToStdout("OntologyInfo Service getVisJs start");
    	
    	SimpleResultSet res = new SimpleResultSet();	
	    
    	String serverType = "";
    	String serverUrl = ""; 
	    try {
	    	if(requestBody.getServerType() == null || requestBody.getServerType().isEmpty()) {
	    		serverType = service_prop.getServerType(); 
	    	} else {
	    		serverType = requestBody.getServerType();
	    	}
	    	
	    	if(requestBody.getUrl() == null || requestBody.getUrl().isEmpty()) {
	    		serverUrl = service_prop.getServerURL(); 
	    	} else {
	    		serverUrl = requestBody.getUrl(); 
	    	}
	    	
	    	SparqlConnection conn = new SparqlConnection();
	    	conn.setDomain(requestBody.getDomain());
	    	conn.addModelInterface(serverType, serverUrl, requestBody.getDataset());
	    	OntologyInfo oInfo = new OntologyInfo(conn);
	    	
	    	res.addResultsJSON( oInfo.toJSON(null) );
	    	res.setSuccess(true);
	    	
	    } catch (Exception e) {
	    	res.setSuccess(false);
	    	res.addRationaleMessage(e.toString());
		    LoggerRestClient.easyLog(logger, "OntologyInfoService", "toJSON exception", "message", e.toString());
		    e.printStackTrace();
	    }
	    
	    return res.toJson();
	}
	
	private void logToStdout (String message) {
		System.out.println(message);
	}
}
