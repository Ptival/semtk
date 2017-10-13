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


package com.ge.research.semtk.resultSet.test;

import static org.junit.Assert.*;

import org.json.simple.JSONObject;
import org.junit.Test;

import com.ge.research.semtk.resultSet.SimpleResultSet;


public class SimpleResultSetTest {

	@Test
	public void test1() {
		SimpleResultSet resultSet = new SimpleResultSet(true, "Monkeys are brown");
		assertEquals(resultSet.getRationaleAsString(","),"Monkeys are brown,");
	}	
	
	@Test
	public void testJsonRationale1() throws Exception {
		SimpleResultSet resultSet = new SimpleResultSet(true, "Monkeys are brown");
		JSONObject j = resultSet.toJson();
		SimpleResultSet rs = SimpleResultSet.fromJson(j);
		assertTrue(rs.getRationaleAsString(",").equals("Monkeys are brown,"));
	}
	
	@Test
	public void testJsonResultString() throws Exception {
		SimpleResultSet resultSet = new SimpleResultSet(true, "Monkeys are brown");
		resultSet.addResult("result", "test");
		JSONObject j = resultSet.toJson();
		SimpleResultSet set2 = SimpleResultSet.fromJson(j);
		assertTrue(set2.getResult("result").equals("test"));
	}	
	
	@Test
	public void testJsonResultInt() throws Exception {
		SimpleResultSet resultSet = new SimpleResultSet(true, "Monkeys are brown");
		resultSet.addResult("result", 100);
		JSONObject j = resultSet.toJson();
		SimpleResultSet set2 = SimpleResultSet.fromJson(j);
		assertTrue(set2.getResultInt("result") == 100);
	}	
	
	@Test
	public void testJsonResultBadName() throws Exception {
		SimpleResultSet resultSet = new SimpleResultSet(true, "Monkeys are brown");
		resultSet.addResult("result", 100);
		JSONObject j = resultSet.toJson();
		SimpleResultSet set2 = SimpleResultSet.fromJson(j);
			
		try {
			set2.getResultInt("BAD_NAME");  // expect an error here
			fail();
		} catch (Exception e) {
			// success
		}
	}	
	
	@Test
	public void addRationale() throws Exception {
		SimpleResultSet resultSet = new SimpleResultSet(false);
			
		try {
			int[] myIntArray = {1,2,3};
			myIntArray[3] = 4;
			fail();
			
		} catch (Exception e) {
			resultSet.addRationaleMessage("testService", "testEndpoint", e);
			assertTrue(resultSet.getRationaleAsString(" ").contains("ArrayIndexOutOfBoundsException"));
			assertTrue(resultSet.getRationaleAsString(" ").contains("SimpleResultSetTest.java"));
		}
	}	
	
}
