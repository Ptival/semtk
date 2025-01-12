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

package com.ge.research.semtk.services.nodeGroupService.requests;

import javax.validation.constraints.NotNull;

import io.swagger.annotations.ApiModelProperty;

public class NodegroupItemStrRequest extends NodegroupRequest {
	@NotNull
	@ApiModelProperty(
			value = "itemStr",
			required = true,
			example = "?sNodeSparqlID")	
	private String itemStr;
	

	public String getItemStr() {
		return this.itemStr;
	}
	
}
