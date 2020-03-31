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


package com.ge.research.semtk.edc.services.hive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.ge.research.semtk.properties.Properties;

@Configuration
@ConfigurationProperties(prefix="hive", ignoreUnknownFields = true)
public class HiveProperties extends Properties {

	private String username; 
	private String password;
	private String executionEngine;  // e.g. mr/tez/spark or blank to not specify
	private Integer loginTimeoutSec;	// login timeout (sec)

	public HiveProperties() {
		super();
		setPrefix("hive");
	}
	
	public void validate() throws Exception {
		super.validate();
		checkNotEmpty("username", username);
		checkNoneMaskValue("password", password);
		checkNone("executionEngine", executionEngine);  // can be empty
		checkNone("loginTimeoutSec", loginTimeoutSec);	// can be empty
	}
	
	public void setUsername(String username){
		this.username = username;
	}
	
	public void setPassword(String password){
		this.password = password;
	}
	
	public void setExecutionEngine(String executionEngine){
		this.executionEngine = executionEngine;
	}

	public void setLoginTimeoutSec(Integer loginTimeoutSec) {
		this.loginTimeoutSec = loginTimeoutSec;
	}
	
	public String getUsername(){
		return username;
	}
	
	public String getPassword(){
		return password;
	}
	
	public String getExecutionEngine(){
		return executionEngine;
	}
	
	public Integer getLoginTimeoutSec() {
		return loginTimeoutSec;
	}
}
