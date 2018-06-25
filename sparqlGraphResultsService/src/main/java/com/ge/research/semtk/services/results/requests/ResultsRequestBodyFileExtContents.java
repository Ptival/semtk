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


package com.ge.research.semtk.services.results.requests;

import com.ge.research.semtk.springutilib.requests.JobIdRequest;

public class ResultsRequestBodyFileExtContents extends JobIdRequest {
	private String contents;

	public String getContents() {
		String retval = "";
		
		if(this.contents != null){ retval = this.contents; }
		
		return retval;
	}

	public void setContents(String contents) {
		this.contents = contents;
	}
	
	
}

