/**
 ** Copyright 2021 General Electric Company
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
package com.ge.research.semtk.plotting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.apache.commons.lang.math.NumberUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import com.ge.research.semtk.resultSet.Table;
import com.ge.research.semtk.utility.Utility;

/**
 * Information for a single Plotly plot spec
 * 
 * Example:
 *     	{   type: "plotly",
 *          name: "line 1",
 *          spec: { data: [{},{}], layout: {}, config: {} }    
 *      }
 */
public class PlotlyPlotSpec extends PlotSpec {

	public static final String TYPE = "plotly";
	
	public static final String JKEY_DATA = "data";
	public static final String JKEY_LAYOUT = "layout";
	public static final String JKEY_CONFIG = "config";
	
	private static final String PREFIX = "SEMTK_TABLE";     // x: "SEMTK_TABLE.col[col_name]"
	private static final String CMD_COL = "col";
	
	String plotSpecJsonStrTemp = null;	// temp string to use while creating a replacement plotSpecJson
		

	public PlotlyPlotSpec(JSONObject plotSpecJson) throws Exception {
		super(plotSpecJson);	
		if(!getType().equals(TYPE)){ throw new Exception("Cannot create PlotlyPlotSpec with type " + getType() + ": expected type " + TYPE); }	
	}
	
	/**
	 * Substitute data and info from a table into a data spec
	 */
	public void applyTable(Table table) throws Exception {
		
		JSONObject spec = (JSONObject) this.getSpec();
		if (spec == null) throw new Exception("Plotly spec json needs a " + JKEY_SPEC + " element");
		JSONArray data = (JSONArray) spec.get(JKEY_DATA);
		if (data == null) throw new Exception("Plotly spec json needs a " + JKEY_DATA + " element");

		this.plotSpecJsonStrTemp = json.toJSONString();  // create a temp string in which to make substitutions (cannot iterate + modify JSON at the same time)
		walkToApplyTable(data, table);
		this.json = Utility.getJsonObjectFromString(plotSpecJsonStrTemp);
	}
	
	/**
	 * Recursively walk json and apply table
	 */
	private void walkToApplyTable(Object json, Table table) throws Exception {
		if (json instanceof JSONObject) {
			// walk members
			JSONObject jObj = (JSONObject) json;
			for (Object key : jObj.keySet()) {
				Object member = jObj.get(key);
				if (member instanceof JSONObject || member instanceof JSONArray) {
					// if member is more json, then recurse
					this.walkToApplyTable(member, table);
				
				} else if (member instanceof String) {
					// found a string field: do the work
					this.applyTableToJsonStringField(jObj, (String)key, table);
				}
			}
			
		} else if (json instanceof JSONArray) {
			// recursively apply to each member of array
			JSONArray jArr = (JSONArray) json;
			for (Object o : jArr) {
				this.walkToApplyTable(o, table);
			}
		}
	}
	
	/**
	 * Process the given existing string field of a JSON object
	 * @param jObj the containing JSON object 
	 * @param key the key of a known string field
	 * @param table replace the string field with data from this table
	 * @throws Exception
	 */
	private void applyTableToJsonStringField(JSONObject jObj, String key, Table table) throws Exception {
		
		String s = (String) jObj.get(key);	// e.g. SEMTK_TABLE.col[colA]
		if (!s.startsWith(PREFIX + ".")) return;
		
		// TODO if already replaced this field, then return
				
		String[] sSplit = s.replaceAll("\\s","").split("[\\.\\[\\]]");  // e.g. split SEMTK_TABLE.col[colA] into ["SEMTK_TABLE", "col", "colA"]
		if(sSplit[0].equals(PREFIX) && sSplit[1].equals(CMD_COL)){
			String colName = sSplit[2].trim();
			int colIndex = table.getColumnIndex(colName);
			if (colIndex == -1) {
				throw new Exception("Plot spec contains column which does not exist in table: '" + colName + "'");
			}
			ArrayList<String> list = new ArrayList<>(Arrays.asList(table.getColumn(colIndex)));
			String columnDataStr;
			if (table.getNumRows() == 0) {
				columnDataStr = "[]";
			} else if(NumberUtils.isNumber(table.getCell(0, colName))){
				columnDataStr = "[" + list.stream().collect(Collectors.joining(", ")) + "]";							// no quotes needed, e.g. [11.1,11.5]
			}else{
				columnDataStr = "[" + list.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(", ")) + "]";  // surround each entry in quotes (e.g. e.g. ["2020-01-24T00:00:00","2020-01-23T00:00:00"])
			}
			plotSpecJsonStrTemp = plotSpecJsonStrTemp.replace("\"" + s + "\"", columnDataStr);  	// replace the SEMTK_TABLE instruction with the data
		}else{
			throw new Exception("Unsupported data specification for plotting: " + s);
		}
	}
	
	/**
	 * Create a sample plot
	 */
	@SuppressWarnings("unchecked")
	public static PlotlyPlotSpec getSample(String name, String graphType, String[] columnNames) throws Exception{
		
		final String[] SUPPORTED_GRAPH_TYPES = {"scatter", "bar"};
		
		// validate
		if(name == null || name.trim().isEmpty()){
			throw new Exception("Cannot create sample plot with no name");
		}
		if(graphType == null || !Arrays.asList(SUPPORTED_GRAPH_TYPES).contains(graphType)){
			throw new Exception("Cannot create sample plot with graph type '" + graphType + "': supported types are " + Arrays.toString(SUPPORTED_GRAPH_TYPES));
		}
		if(columnNames == null || columnNames.length < 2){
			throw new Exception("Cannot create sample plot with fewer than 2 column names");
		}
		
		// create trace using first 2 columns	 TODO could add more traces
		JSONObject traceJson = new JSONObject();
		traceJson.put("type", graphType);
		traceJson.put("x", "SEMTK_TABLE.col[" + columnNames[0] + "]");
		traceJson.put("y", "SEMTK_TABLE.col[" + columnNames[1] + "]");
		
		// add trace(s) to data object 
		JSONArray dataJsonArr = new JSONArray();
		dataJsonArr.add(traceJson);
		
		// add data, config, layout to spec object
		JSONObject specJson = new JSONObject();
		specJson.put(JKEY_DATA, dataJsonArr);
		specJson.put(JKEY_CONFIG, Utility.getJsonObjectFromString("{ \"editable\": true }"));
		specJson.put(JKEY_LAYOUT, Utility.getJsonObjectFromString("{ \"title\": \"Sample Plot\" }"));
		
		// add type, name, spec to plotSpec object
		JSONObject plotSpecJson = new JSONObject();
		plotSpecJson.put(JKEY_TYPE, TYPE);
		plotSpecJson.put(JKEY_NAME, name);
		plotSpecJson.put(JKEY_SPEC, specJson);
		
		return new PlotlyPlotSpec(plotSpecJson);
	}

}