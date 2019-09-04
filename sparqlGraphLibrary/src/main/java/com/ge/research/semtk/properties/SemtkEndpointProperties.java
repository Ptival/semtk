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


package com.ge.research.semtk.properties;

// meant to be inherited into a spring boot microservice property object
// properties for a triple-store endpoint
public class SemtkEndpointProperties extends EndpointProperties {
	private String endpointDomain = "";
	private String endpointDataset = "";
	
	
	public String getEndpointDomain() {
		return endpointDomain;
	}
	public void setEndpointDomain(String jobEndpointDomain) {
		this.endpointDomain = jobEndpointDomain;
	}
	
	public String getEndpointDataset() {
		return endpointDataset;
	}
	public void setEndpointDataset(String jobEndpointDataset) {
		this.endpointDataset = jobEndpointDataset;
	}
	
	public void validate() throws Exception {
		super.validate();
		checkNone("endpointDomain", endpointDomain);
		checkNotEmpty("endpointDataset", endpointDataset);
	}
}
