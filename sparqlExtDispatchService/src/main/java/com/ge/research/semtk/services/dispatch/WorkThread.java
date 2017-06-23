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

import java.net.ConnectException;

import org.json.simple.JSONObject;

import com.ge.research.semtk.belmont.AutoGeneratedQueryTypes;
import com.ge.research.semtk.edc.client.EndpointNotFoundException;
import com.ge.research.semtk.resultSet.TableResultSet;
import com.ge.research.semtk.sparqlX.asynchronousQuery.AsynchronousNodeGroupBasedQueryDispatcher;
import com.ge.research.semtk.sparqlX.asynchronousQuery.DispatcherSupportedQueryTypes;


public class WorkThread extends Thread {
	AsynchronousNodeGroupBasedQueryDispatcher dsp;
	JSONObject constraintJson;

	DispatcherSupportedQueryTypes myQT;
	String targetObjectSparqlID;
	String rawSparqlQuery;
	
	
	public WorkThread(AsynchronousNodeGroupBasedQueryDispatcher dsp, JSONObject constraintJson, DispatcherSupportedQueryTypes qt){
		this.dsp = dsp;
		this.myQT = qt;
		this.constraintJson = constraintJson;
		
	}
	
	public void setTargetObjectSparqlID(String targetObjectSparqlID) throws Exception{
		if(targetObjectSparqlID == null || targetObjectSparqlID == "" || targetObjectSparqlID.isEmpty()){
			throw new Exception("Target object for filter constraint was null or empty.");
		}
		this.targetObjectSparqlID = targetObjectSparqlID;
	}
	
	public void setRawSparqlSquery(String query) throws Exception{
		if(query == null || query == "" || query.isEmpty()){
			throw new Exception("Given SPARQL query was null or empty.");
		}
		this.rawSparqlQuery = query;
	}
	
    public void run() {

		try {
			if(this.rawSparqlQuery != null){
				// a query was passed. use it.
				TableResultSet trs = this.dsp.executePlainSparqlQuery(rawSparqlQuery);
			}
			
			else{
				// query from the node group itself. 
				TableResultSet trs = this.dsp.execute(constraintJson, this.myQT, targetObjectSparqlID);
			}	
		} catch (Exception e) {
			e.printStackTrace();		
		}
	
    	System.out.println("Setting dispatcher to null in WorkThread");
    	this.dsp = null;
    }


}