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

package com.ge.research.semtk.services.results;

import com.ge.research.semtk.utility.LocalLogger;

public class ResultsRequestBodyMaxRows extends ResultsRequestBody {
	
	public Integer maxRows;
	public Integer startRow = 0;	

	public Integer getMaxRows() {
		return maxRows;
	}

	public void setMaxRows(Integer maxRows) {
		this.maxRows = maxRows;
	}	
	
	public void setStartRow(Integer startRow){
		if(startRow == null || startRow < 0){ 
			this.startRow = 0;
			LocalLogger.logToStdErr("start value passed was either null or less than zero. Zero was used instead.");
		}
		else{
			this.startRow = startRow;
		}
	}
	
	public Integer getStartRow(){
		return this.startRow;
	}
	
}
