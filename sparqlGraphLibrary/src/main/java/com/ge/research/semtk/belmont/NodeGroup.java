/**
 ** Copyright 2016-2021 General Electric Company
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


package com.ge.research.semtk.belmont;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.UUID;
import java.util.regex.Pattern;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;


import com.ge.research.semtk.belmont.runtimeConstraints.RuntimeConstraintManager;
import com.ge.research.semtk.belmont.runtimeConstraints.RuntimeConstraintMetaData;
import com.ge.research.semtk.load.NothingToInsertException;
import com.ge.research.semtk.load.utility.ImportSpec;
import com.ge.research.semtk.load.utility.UriResolver;
import com.ge.research.semtk.ontologyTools.OntologyClass;
import com.ge.research.semtk.ontologyTools.OntologyInfo;
import com.ge.research.semtk.ontologyTools.OntologyName;
import com.ge.research.semtk.ontologyTools.OntologyPath;
import com.ge.research.semtk.ontologyTools.OntologyProperty;
import com.ge.research.semtk.ontologyTools.Triple;
import com.ge.research.semtk.ontologyTools.ValidationException;
import com.ge.research.semtk.sparqlX.SparqlConnection;
import com.ge.research.semtk.sparqlX.SparqlEndpointInterface;
import com.ge.research.semtk.sparqlX.SparqlToXUtils;
import com.ge.research.semtk.utility.LocalLogger;

public class NodeGroup {
	private static final String JSON_KEY_NODELIST = "sNodeList";
	private static final String JSON_KEY_UNIONHASH = "unionHash";
	
	// version 9: added snodeOptionals MINUS
	// version 10: accidentally wasted in a push
	// version 11: importSpec dataValidator
	// version 12: unions
	// version 13: removed node.nodeName
	private static final int VERSION = 13;
	
	// actually used to keep track of our nodes and the nomenclature in use. 
	private ArrayList<Node> nodes = new ArrayList<Node>();
	private HashMap<String, Node> idToNodeHash = new HashMap<String, Node>();
	private int limit = 0;
	private int offset = 0;
	private ArrayList<OrderElement> orderBy = new ArrayList<OrderElement>();

	private ArrayList<Node> orphanOnCreate = new ArrayList<Node>();
	private HashMap<String, String> prefixHash = new HashMap<String, String>();
	private int prefixNumberStart = 0;
	
	// used for sparql generation
	private SparqlConnection conn = null;
	
	// BIG design question with a long complex history.
	// oInfo is now needed to generate SPARQL if SparqlConnection has owlImports, 
	//    because only oInfo knows all the graphs it imported.
	// oInfo is therefore grabbed whenever one is sent in for validation or inflating.
	// For historical reasons, many functions require oInfo params. 
	//    These could be deprecated in favor of simpler signatures with no oInfo.
	private OntologyInfo oInfo = null;
	
	// Unions are defined by an integer key and a list of UnionValueStrings that specify the branch points
	private HashMap<Integer, ArrayList<String>> unionHash = new HashMap<Integer,  ArrayList<String>>();
	// not saved.  generated when needed
	// for every Node, NodeItem or PropItem: list if union integer keys sorted "closest" (leaf) to furthest (parent)
	private HashMap<String, ArrayList<Integer>> tmpUnionMemberHash = new HashMap<String, ArrayList<Integer>>();
	// same key as tmpUnionMemberHash.  Val us a hash from unionKey to parentStr.  Parent is the branch in tmpUnionMemberHash
	private HashMap<String, HashMap<Integer, String>> tmpUnionParentHash = new HashMap<String, HashMap<Integer, String>>();
	
	public NodeGroup(){
	}
	
	/*
	 * Simple check that Json looks right
	 */
	public static boolean isNodeGroup(JSONObject jObj) {
		return jObj.containsKey(JSON_KEY_NODELIST);
	}
	
	public static JSONArray extractNodeList(JSONObject jObj) {
		return (JSONArray) jObj.get(JSON_KEY_NODELIST);
	}
	
	public int getPrefixNumberStart(){
		return this.prefixNumberStart;
	}
	
	/**
	 * Create a NodeGroup from JSON
	 * 
	 * DANGER: no connection info, which is now required for multi-graph sparql generation
	 *         Strongly consider using SparqlGraphJson.getNodeGroup()
	 * @throws Exception 
	 */
	public static NodeGroup getInstanceFromJson(JSONObject json) throws Exception  {
		return NodeGroup.getInstanceFromJson(json, null);
	}
	
	/**
	 * Create a NodeGroup from JSON
	 * 
	 * DANGER: no connection info, which is now required for multi-graph sparql generation
	 *         Strongly consider using SparqlGraphJson.getNodeGroup()
	 * @throws Exception 
	 */
	public static NodeGroup getInstanceFromJson(JSONObject json, OntologyInfo uncompressOInfo) throws Exception {
		NodeGroup nodegroup = new NodeGroup();
		nodegroup.addJsonEncodedNodeGroup(json, uncompressOInfo);
		return nodegroup;
	}
	
	/**
	 * Create a NodeGroup from the (JSON) results of a CONSTRUCT query.
	 * @param jobj the output of a SPARQL construct query (assume key is @graph)
	 * @return a NodeGroup containing the construct query results
	 * @throws Exception 
	 * 
	 * Deprecated. Only Justin knows how this was supposed to work or precisely what it does.
	 */
	@Deprecated
	public static NodeGroup fromConstructJSON(JSONObject jobj) throws Exception {
				
		if(jobj == null){
			throw new Exception("Cannot create NodeGroup from null JSON object");
		}		
		
		if(!jobj.containsKey("@graph")){
			LocalLogger.logToStdErr("there was not @graph key in the JSON-LD. assuming this is intentional, nothing was added.");
			return new NodeGroup();
		}
		
		// get the contents of @graph
		JSONArray nodeArr = (JSONArray) jobj.get("@graph");  
		if(nodeArr == null){
			throw new Exception("No @graph key found when trying to create node group from construct query");
		}

		NodeGroup nodeGroup = new NodeGroup();		
		HashMap<String,Node> nodeHash = new HashMap<String,Node>(); // maps node URI to Node
		
		// first pass - gather each node's id, type, and primitive properties (but skip its properties that are node items)
		for (int i = 0; i < nodeArr.size(); i++) {
			
			JSONObject nodeJson = (JSONObject) nodeArr.get(i);	
			// e.g. sample nodeJSON:
			// {
			//	"@id":"http:\/\/research.ge.com\/print\/data#Cell_ABC",
			//	"@type":[{"@id":"http:\/\/research.ge.com\/print\/testconfig#Cell"}],  ... IN VIRTUOSO 7.2.5+, NO @id HERE
			//	"http:\/\/research.ge.com\/print\/testconfig#cellId":[{"@value":"ABC","@type":"http:\/\/www.w3.org\/2001\/XMLSchema#string"}],
			//	"http:\/\/research.ge.com\/print\/testconfig#screenPrinting":[{"@id":"http:\/\/research.ge.com\/print\/data#Prnt_ABC_2000-01-01"}],
			//	"http:\/\/research.ge.com\/print\/testconfig#sizeInches":[2]
			//	}
			
			// gather basic node info
			String instanceURI = nodeJson.get("@id").toString();  // node id
						
			// this format differs between Virtuoso 7.2 and 7.2.5.  Support both.
			String classURI;
			Object typeEntry0 = NodeGroup.getAsArray(nodeJson, "@type").get(0);
			if(typeEntry0 instanceof JSONObject && ((JSONObject)typeEntry0).containsKey("@id")){
				classURI = ((JSONObject)typeEntry0).get("@id").toString(); // "@type" : [ { "@id": "http://research.ge.com/energy/turbineeng/configuration#TestType"} ]  (in Virtuoso 7.2)
			}else{
				classURI = typeEntry0.toString(); // "@type" : [ "http://research.ge.com/energy/turbineeng/configuration#TestType" ] }   (in Virtuoso 7.2.5+)
			}						
			String name = (new OntologyName(classURI)).getLocalName();									
			
			// create the Node and add it to the NodeGroup
			Node node = new Node(name, null, null, classURI, nodeGroup); 
			node.setInstanceValue(instanceURI);		
			nodeHash.put(instanceURI, node); 				// add node to node hash
			nodeGroup.addOneNode(node, null, null, null, false);	// add node to node group
						
			ArrayList<PropertyItem> properties = new ArrayList<PropertyItem>();
			@SuppressWarnings("unchecked")
			Iterator<String> keysItr = ((Iterator<String>) nodeJson.keySet().iterator());
		    while(keysItr.hasNext()) {
		    			    	
		        String key = keysItr.next();		        
		        if(key.equals("@id") || key.equals("@type") || key.equals("@Original-SparqlId")){
		        	continue;  // already got node id and type above, so skip
		        }		        
		        		        
		        // primitive properties are like this:
		        // e.g. KEY=http://research.ge.com/print/testconfig#material VALUE=[{"@value":"Red Paste","@type":"http:\/\/www.w3.org\/2001\/XMLSchema#string"} {"@value":"Blue Paste","@type":"http:\/\/www.w3.org\/2001\/XMLSchema#string"}]
		        		         
		        JSONArray valueArray = NodeGroup.getAsArray(nodeJson, key);
		        try {
		        	if(!((JSONObject)((valueArray).get(0))).containsKey("@type")){  
		        		continue;  // no @type then this is not a primitive property   
		        	}	
		        } catch (ClassCastException e) {
		        	continue;    // can't be cast to JSONObject then it's also a primitive
		        }
	        	
		        PropertyItem property = null;
		        for(int j = 0; j < valueArray.size(); j++){ 
		        	JSONObject valueJSONObject = (JSONObject)((valueArray).get(j));	 
		        	if(property == null){  // only create property once
		        		String relationship = key; 		// e.g. http://research.ge.com/print/testconfig#material
		        		String propertyValueType = valueJSONObject.get("@type").toString();	// e.g. http://www.w3.org/2001/XMLSchema#string
		        		String relationshipLocal = new OntologyName(relationship).getLocalName();   // e.g. pasteMaterial
		        		String propertyValueTypeLocal = new OntologyName(propertyValueType).getLocalName();	// e.g. string
		        		property = new PropertyItem(relationshipLocal, XSDSupportedType.getMatchingValue(propertyValueTypeLocal), propertyValueType, relationship); 		        		
		        	}
		        	String propertyValue = valueJSONObject.get("@value").toString();  // e.g. Ce0.8Sm0.2 Oxide Paste
		        	property.addInstanceValue(propertyValue);		        
		        }	
		        if (property.getSparqlID().isEmpty()) {
		        	property.setSparqlID(BelmontUtil.generateSparqlID(property.getKeyName(), nodeGroup.getAllVariableNames(node, property)));
		        }
		        property.setIsReturned(true);	// note - Javascript had this inside the for loop
		        properties.add(property);		// note - Javascript had this inside the for loop
		    }
		    node.setProperties(properties);  // add the properties to the node		    
		}
		

		// second pass - gather properties that are links to other nodes
		// this can only be done after all of the nodes are created		
		for (int i = 0; i < nodeArr.size(); i++) {
			
			JSONObject nodeJson = (JSONObject) nodeArr.get(i);	
		    
			// this is the node to link from - retrieve it from the hash
        	String fromNodeURI = nodeJson.get("@id").toString(); 
        	Node fromNode = nodeHash.get(fromNodeURI);  	        
			
			ArrayList<NodeItem> nodeItems = new ArrayList<NodeItem>();
			
		    @SuppressWarnings("unchecked")
			Iterator<String> keysItr = (Iterator<String>) nodeJson.keySet().iterator();
		    
		    while(keysItr.hasNext()) {
		    			    	
		        String key = keysItr.next();
		        	        		        
		        if(key.equals("@id") || key.equals("@type") || key.equals("@Original-SparqlId")){
		        	continue;  // already got node id and type above, so skip
		        }		        

		        // node items are in this format:
		        // e.g. KEY=http://research.ge.com/print/testconfig#screenPrinting VALUE=[{"@id":"http:\/\/research.ge.com\/print\/data#ScrnPrnt_ABC"}]        		        
		        
		        JSONArray valueArray = NodeGroup.getAsArray(nodeJson, key);
		        try {
		        	if(((JSONObject)((valueArray).get(0))).containsKey("@type")){  // check the first element - if has @type then this is not a node item
		        		continue;  
		        	}
		        } catch (ClassCastException e) {
		        	continue;    // can't be cast to JSONObject then not a node item
		        }
		        
		        NodeItem nodeItem = null;
		        for(int j = 0; j < valueArray.size(); j++){
		        	JSONObject valueJSONObject = ((JSONObject)(valueArray).get(j));	// the value is an array 	      		        

			        String relationship = key;  // e.g. http://research.ge.com/print/testconfig#screenPrinting
			        String relationshipLocal = (new OntologyName(relationship)).getLocalName(); // e.g. screenPrinting
			        // I added this for neptune.
			        // No idea if it actually works.  No one uses this.
			        // -Paul 
			        if (valueJSONObject.containsKey("@id")) {
			        	String toNodeURI = valueJSONObject.get("@id").toString(); // e.g. http://research.ge.com/print/data#ScrnPrnt_ABC
				        Node toNode = nodeHash.get(toNodeURI);  
				        String toNodeClassURI = toNode.getFullUriName(); // e.g. http://research.ge.com/print/testconfig#ScreenPrinting		        	
				        
			        	if(nodeItem == null){  // only create node item once
					        nodeItem = new NodeItem(relationship, (new OntologyName(toNodeClassURI)).getLocalName(), toNodeClassURI); 
					        nodeItem.setConnected(true);
				        	nodeItem.setConnectBy(relationshipLocal);
					        nodeItem.setUriConnectBy(relationship);				        
			        	}
			        	nodeItem.pushNode(toNode);  // add all instance values to it
			        }
		        }		  
				nodeItems.add(nodeItem);
		    }
		    fromNode.setNodeItems(nodeItems);	
		    
		    if(fromNode.getInstanceValue() != null){  // differs from javascript, may need to modify later
		    	fromNode.setIsReturned(true);  
		    }

		}		    		  
		
		return nodeGroup;
	}
	
	/**
	 * If jObj[fieldName] is an array, return it.  Else put it in an array and return that.
	 * Helper function for different style JSON-LD.
	 * Some versions of Virtuoso return single values of @type as-is, and other versions return an array of types, even if there's just one.
	 * @param jObj
	 * @param fieldName
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private static JSONArray getAsArray(JSONObject jObj, String fieldName) {
		Object field = jObj.get(fieldName);
		if (field instanceof JSONArray) {
			return ((JSONArray) field);
		} else {
			JSONArray ret = new JSONArray();
			ret.add(field);
			return ret;
		}
	}
	
	public void addOrphanedNode(Node node){
		// add to the onrphanOncreateList
		this.orphanOnCreate.add(node);
		// also, add to the list of known nodes
		this.nodes.add(node);
		this.idToNodeHash.put(node.getSparqlID(), node);
	}
	
	/**
	 * Use JSON fuctionality to implement deepCopy
	 * @param nodegroup
	 * @return a deep copy
	 * @throws Exception 
	 * @
	 */
	public static NodeGroup deepCopy(NodeGroup nodegroup) throws Exception  {
		NodeGroup copy = new NodeGroup();
		copy.addJsonEncodedNodeGroup(nodegroup.toJson());
		
		// connection
		if (nodegroup.conn != null) {
			SparqlConnection conn = new SparqlConnection();
			conn.fromJson(nodegroup.conn.toJson());
			copy.setSparqlConnection(conn);
		}
		
		// oInfo is a pointer.  It is the state of the nodegroup but not actually deep copied.
		copy.oInfo = nodegroup.oInfo;
		
		return copy;
	}
	
	/**
	 * Clear everything except names, types, node connections
	 */
	public void reset() {
		this.limit = 0;
		this.offset = 0;
		this.orderBy = new ArrayList<OrderElement>();
		for (Node n : this.nodes) {
			n.reset();
		}
		this.prefixHash = new HashMap<String, String>();
		this.unionHash = new HashMap<Integer, ArrayList<String>>();
		this.tmpUnionMemberHash = new HashMap<String, ArrayList<Integer>>();
	}
	public int getLimit() {
		return this.limit;
	}

	public void setLimit(int limit) {
		this.limit = limit;
	}
	
	public int getOffset() {
		return this.offset;
	}
	
	public void clearOrderBy() {
		this.orderBy = new ArrayList<OrderElement>();
	}
	
	public void appendOrderBy(String sparqlID) throws Exception {
		this.appendOrderBy(sparqlID, "");
	}
	
	public void appendOrderBy(OrderElement e) throws Exception {
		this.appendOrderBy(e.getSparqlID(), e.getFunc());
	}
	
	public void appendOrderBy(String sparqlID, String func) throws Exception {
		if (! this.getReturnedSparqlIDs().contains(sparqlID)) {
			throw new Exception(String.format("SparqlID can't be found in nodegroup: '%s'", sparqlID));
			
		} else if (this.orderBy.contains(sparqlID)) {
			throw new Exception(String.format("SparqlID can't be added to ORDER BY twice: '%s'", sparqlID));

		} else {
			this.orderBy.add(new OrderElement(sparqlID, func));
		}
	}
	
	public void removeInvalidOrderBy() {
		ArrayList<OrderElement> keep = new ArrayList<OrderElement>();
		ArrayList<String> ids = this.getReturnedSparqlIDs();
		for (OrderElement e : this.orderBy) {
			if (ids.contains(e.getSparqlID())) {
				keep.add(e);
			}
		}
		
		this.orderBy = keep;
	}
	
	public void validateOrderBy() throws Exception {		
		ArrayList<String> ids = this.getReturnedSparqlIDs();
		for (OrderElement e : this.orderBy) {
			if (!ids.contains(e.getSparqlID())) {
				throw new ValidationException(String.format("Invalid SparqlID in ORDER BY : '%s'", e.getSparqlID()));
			}
		}
	}
	
	/**
	 * Set orderBy to every returned item.
	 * (To ensure a deterministic return order for OFFSET)
	 * @throws Exception
	 */
	public void orderByAll() throws Exception {
		this.clearOrderBy();
		for (String id : this.getReturnedSparqlIDs()) {
			this.appendOrderBy(id);
		}
	}

	public void setOffset(int offset) {
		this.offset = offset;
	}
	
	/**
	 * Apply < > if this URI contains a protocol (http://uri) instead of a prefix (prefix:uri)
	 * @param originalUri
	 * @return
	 */
	private String applyAngleBrackets(String originalUri) {
		if (originalUri.contains("://")) {
			return "<" + originalUri + ">";
		} else {
			return originalUri;
		}
	}
	/**
	 * Apply prefixHash to a URI if it contains '#'
	 * @param originalUri
	 * @return
	 * @throws Exception
	 */
	private String applyPrefixing(String originalUri) throws Exception {
		String retval = "";
		
		if (originalUri == null ) {
			throw new Exception("found unexpected null URI");
		}
		
		else if (!originalUri.contains("#")) {
			return originalUri;
			
		} else {
			// get the chunks and build the prefixed string.
			String[] chunks = originalUri.split("#");
			String pre = this.prefixHash.get(chunks[0]);
			
			if (pre == null) {
				// prefixing failed.
				retval = originalUri;  
			}
			else if(chunks.length > 1 ){
				retval = pre + ":" + chunks[1];
			}
			else{
				retval = pre + ":";
			}
		}
		
		return retval;
	}
	
	/**
	 * Apply qualifier.   Be sure to do prefixing first.
	 * @param originalUri
	 * @return
	 * @throws Exception
	 */
	private String applyQualifier(String objPropUri, String qualifier) throws Exception {		
		if (objPropUri == null ) {
			throw new Exception("found unexpected null URI");
			
		} else {
			switch (qualifier) {
				case "":
					return objPropUri;
				case "*":
				case "+":
				case "?":
					return objPropUri + qualifier;
				case "^":
					return qualifier +  objPropUri;
				
				default: 
					throw new Exception("Found unexpected object property qualifier: " + qualifier);
			}
		}
	}
	
	/**
	 * Prepend DEFAULT_URI_PREFIX if there is no prefix
	 * @param originalUri
	 * @return
	 */
	private String applyBaseURI(String originalUri) {
		
		if(!originalUri.contains("#") && !originalUri.contains("://")) {
			return UriResolver.DEFAULT_URI_PREFIX + originalUri;
			
		} else {
			return originalUri;
		}
	}
	
	private String legalizePrefixName(String suggestion) {
		// replace illegal characters with "_"
		
		String ret = suggestion.replaceAll("[^A-Za-z_0-9]", "_");
		if (! Character.isLetter(ret.charAt(0))) {
			ret = "a" + ret;
		}
		
		return ret;
	}
	
	public void addToPrefixHash(String prefixName, String prefixValue) {
		this.prefixHash.put(prefixValue, prefixName);
	}
	
	public void addToPrefixHash(String prefixedUri){
		// from the incoming string, remove the local fragment and then try to add the rest to the prefix hash.
		if(prefixedUri == null){ return; }
		if(!prefixedUri.contains("#")){ return; }
		
		String[] chunks = prefixedUri.split("#");
		
		// found a new prefix
		if(!this.prefixHash.containsKey(chunks[0])) {
			// create a new prefix name
			String [] fragments = chunks[0].split("/");
			String newPrefixName = fragments[fragments.length - 1];
			
			// make sure prefix starts with a number
			newPrefixName = this.legalizePrefixName(newPrefixName);
			
			// make sure new prefix name is unique
			if (this.prefixHash.containsValue(newPrefixName)) {
				int i=0;
				while (this.prefixHash.containsValue(newPrefixName + "_" + i)) {
					i++;
				}
				newPrefixName = newPrefixName + "_" + i;
			}
			
			//String newPrefixName = "pre_" + this.prefixNumberStart;
			this.prefixNumberStart += 1;  // also obsolete I think
			
			this.prefixHash.put(chunks[0], newPrefixName);
			
			//LocalLogger.logToStdErr("adding prefix: " + newPrefixName + " with key " + chunks[0] + " from input " + prefixedUri);
		}
	}
	
	public void rebuildPrefixHash(HashMap<String, String> startingMap){
		
		this.prefixHash = new HashMap<String, String>();		// x out the old map.
		
		this.prefixHash = startingMap;							// replace it.
		this.prefixNumberStart = startingMap.size();
		addAllToPrefixHash();
		
	}
	
	private void addAllToPrefixHash(){

		this.addToPrefixHash(UriResolver.DEFAULT_URI_PREFIX);   // make sure to force the inclusion of the old ones.

		this.addToPrefixHash("XMLSchema", "http://www.w3.org/2001/XMLSchema");
		this.addToPrefixHash("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns");
		this.addToPrefixHash("rdfs", "http://www.w3.org/2000/01/rdf-schema");
		
		for(Node n : this.nodes){

			if(n.getInstanceValue() != null && n.getInstanceValue().contains("#")){
				this.addToPrefixHash(n.getInstanceValue());
			}	
			
			// add the prefix for each node.
			this.addToPrefixHash(n.getFullUriName());
			
			// add subclasses if they're knowable through an oInfo
			if (this.oInfo != null) {
				for (String subClass : this.oInfo.getSubclassNames(n.getFullUriName())) {
					this.addToPrefixHash(subClass);
				}
			}
			// add the URIs for the properties as well:
			for(PropertyItem pi : n.getPropertyItems()){
				this.addToPrefixHash(pi.getUriRelationship());
			}
			// add the URIs for the node items
			for(NodeItem ni : n.getNodeItemList()){
				this.addToPrefixHash(ni.getUriConnectBy());
			}
		}

	}
	
	public HashMap<String, String> getPrefixHash(){
		
		if(this.prefixHash != null && this.prefixHash.size() != 0){
			return this.prefixHash;
		}
		else{
			this.buildPrefixHash();		// create something to send.
			return this.prefixHash;
		}
		
	}
	
	public void buildPrefixHash(){
		
		if(this.prefixHash.size() != 0){
			return;
		}
		else{
		// if possible, add the default prefix and user prefix:
			addAllToPrefixHash();
		}
	}
	
	public String generateSparqlPrefix(){
		StringBuilder retval = new StringBuilder();
		
		// check that it is built!
		if(this.prefixHash.size() == 0) { this.buildPrefixHash(); }
		
		for(String k : this.prefixHash.keySet()){
			retval.append("prefix " + this.prefixHash.get(k) + ":<" + k + "#>\n");
		}
				
		return(retval.toString());
	}
	
	public void addJsonEncodedNodeGroup(JSONObject jobj) throws Exception {
		this.addJsonEncodedNodeGroup(jobj, null);
	}
	
	public void addJsonEncodedNodeGroup(JSONObject jobj, OntologyInfo uncompressOInfo) throws Exception {
		this.oInfo = uncompressOInfo;
		HashMap<String, String> changedHash = new HashMap<String, String>();
		
		// For backwards compatibility to I-don't-know-where, 
		// we'll try to resolve sparqlId collisions as long as neither nodegroup
		// has unions.
		// Reality: we don't append nodegroups
		boolean unionFlag = this.unionHash.size() > 0 || (jobj.containsKey(JSON_KEY_UNIONHASH) && ((JSONObject) jobj.get(JSON_KEY_UNIONHASH)).keySet().size() > 0);
		
		if (!unionFlag) {
			this.resolveSparqlIdCollisions(jobj, changedHash);
		}

		int version = Integer.parseInt(jobj.get("version").toString());
		if (version > NodeGroup.VERSION) {
			throw new Exception (String.format("NodeGroup.java service layer reads NodeGroups through version %d. Nodegroup version is %d.", NodeGroup.VERSION, version));
		}
		
		if (jobj.containsKey("limit")) {
			this.setLimit(Integer.parseInt(jobj.get("limit").toString()));
		}
		
		if (jobj.containsKey("offset")) {
			this.setOffset(Integer.parseInt(jobj.get("offset").toString()));
		}
		
		// attempt to add the nodes, using "changedHash" as a guide for IDs.Integer.parseInt
		this.addJson((JSONArray) jobj.get(JSON_KEY_NODELIST), uncompressOInfo); 
		
		if (jobj.containsKey("orderBy")) {
			JSONArray oList = (JSONArray) jobj.get("orderBy");
			for (int i=0; i < oList.size(); i++) {
				JSONObject j = (JSONObject) oList.get(i);
				OrderElement e = new OrderElement(j);
				this.appendOrderBy(e); 
			}
		}
		
		this.validateOrderBy();
		
		// unionHash
		this.unionHash = new HashMap<Integer, ArrayList<String>>();
        if (version >= 12) {
        	// unionHash
    		JSONObject jobUnionHash = (JSONObject) jobj.get(JSON_KEY_UNIONHASH);
            for (Object k : jobUnionHash.keySet()) {
            	ArrayList<String> val = new ArrayList<String>();
            	for (Object s : (JSONArray)jobUnionHash.get(k)) {
            		val.add((String) s);
            	}
                this.unionHash.put(Integer.parseInt((String)k), val);
            }
		} 
	}
	
	public void addJson(JSONArray nodeArr) throws Exception  {
		this.addJson(nodeArr, null);
	}
	
	/**
	 * Add json to a nodegroup
	 * @param nodeArr
	 * @param uncompressOInfo If non-null, use this to uncompress any Node properties
	 * @throws Exception 
	 * @
	 */
	public void addJson(JSONArray nodeArr, OntologyInfo uncompressOInfo) throws Exception  {
		this.oInfo = uncompressOInfo;
		for (int j = 0; j < nodeArr.size(); ++j) {
			JSONObject nodeJson = (JSONObject) nodeArr.get(j);
			
			Node curr  = new Node(nodeJson, this, uncompressOInfo);
			Node check = this.getNodeBySparqlID(curr.getSparqlID());
			
			// create nodes we have never seen
			if(check == null){
				this.addOneNode(curr, null, null, null, false);
			}
			// modify the existing node:
			else{

				// if the node is in both the nodesList and orphan list, modify it. 
				check = null;
				for(Node nd : this.orphanOnCreate){
					if(curr.sparqlID.equals(nd.sparqlID)){						
						check = nd;
						break;
					}
				}
				if(check != null){
					// remove from the orphan list. we do not want to mod this node more than once. 
				//	this.orphanOnCreate.remove(check);
					check.updateFromJson(nodeJson);
				}
				else{
					throw new Exception( "--uncreated node referenced: " + curr.sparqlID );
				}
					
			}
			
		}
		this.orphanOnCreate.clear();
	}
	
	/**
	 * This seems to only be used when adding more json to a nodegroup.
	 * It is retained for backwards compatibility
	 * Paul sep-2020
	 * @param jobj
	 * @param changedHash
	 * @return
	 */
	private JSONObject resolveSparqlIdCollisions(JSONObject jobj, HashMap<String, String> changedHash) {
		// loop through a json object and resolve any SparqlID name collisions
		// with this node group.
		JSONObject retval = jobj;
		
		HashSet<String> tempHash = this.getAllVariableNames();
		
		if (tempHash.isEmpty()) {
			return retval;
		}
	
		JSONArray nodeArr = (JSONArray)jobj.get(JSON_KEY_NODELIST);
		// loop through the nodes in the JSONArray
		for(int k = 0; k < nodeArr.size(); k += 1){
			JSONObject jnode = (JSONObject) nodeArr.get(k);
			
			jnode = BelmontUtil.updateSparqlIdsForJSON(jnode, "SparqlID", changedHash, tempHash);
			
			// iterate over property objects
			JSONArray propArr = (JSONArray) jnode.get("propList");
			
			for (int j = 0; j < propArr.size(); ++j) {
				JSONObject prop = (JSONObject) propArr.get(j);
				prop = BelmontUtil.updateSparqlIdsForJSON(prop, "SparqlID", changedHash, tempHash);
			}
			
			// and the node list			
			JSONArray nodeItemArr = (JSONArray) jnode.get("nodeList");
			
			for (int j = 0; j < nodeItemArr.size(); ++j) {
				JSONObject node = (JSONObject) nodeItemArr.get(j);
				JSONArray nodeConnections = (JSONArray)node.get("SnodeSparqlIDs");
				for(int m = 0; m < nodeConnections.size(); m += 1){
					// this should update the values we care about
					BelmontUtil.updateSparqlIdsForJSON(nodeConnections, m, changedHash, tempHash);
				}
			}
		}
		
		return retval;
	}
    public ArrayList<Node> getNodeList(){
        return this.nodes;
    }
    
	/**
	 * Create a new union
	 * @return an integer key
	 */
	public int newUnion() {
        int ret = 1;
        while (this.unionHash.containsKey(ret)) {
            ret += 1;
        }
        this.unionHash.put(ret, new ArrayList<String>());
        return ret;
    }
	
	public void rmUnion(int id) {
       this.unionHash.remove(id);
    }
	
	// rm item from unionHash
    // silent if item is not in unionHash
    public void rmFromUnions(Node snode, NodeItem item, Node target) {
        String lookup1 = new NodeGroupItemStr(snode, item, target, true).getStr();
        String lookup2 = new NodeGroupItemStr(snode, item, target, false).getStr();

        this.rmFromUnions(lookup1);
        this.rmFromUnions(lookup2);
    }
    
    public void rmFromUnions(Node snode, PropertyItem item) {
        String lookup = new NodeGroupItemStr(snode, item).getStr();
        this.rmFromUnions(lookup);
    }
    
    public void rmFromUnions(Node snode) {
        String lookup = new NodeGroupItemStr(snode).getStr();
        this.rmFromUnions(lookup);
    }
    
    private void rmFromUnions(String lookup) {
        for (Integer key : this.unionHash.keySet()) {
            for (String i : this.unionHash.get(key)) {

                if (i.equals(lookup)) {
                	ArrayList<String> val = this.unionHash.get(key);
                    val.remove(i);
                    if (val.size() == 0) {
                        this.rmUnion(key);
                    }
                    return;
                }
            }
        }
    }
	
    public void addToUnion(int id, Node snode, NodeItem nItem, Node target, boolean reverseFlag) {
    	this.addToUnion(id, new NodeGroupItemStr(snode, nItem, target, reverseFlag).getStr());
    }
	public void addToUnion(int id, Node snode, PropertyItem pItem) {
		this.addToUnion(id, new NodeGroupItemStr(snode, pItem).getStr());
    }
	public void addToUnion(int id, Node snode) {
        this.addToUnion(id, new NodeGroupItemStr(snode).getStr());
    }
	public void addToUnion(int id, String keyStr) {
		if (!this.unionHash.containsKey(id)) {
			this.unionHash.put(id, new ArrayList<String>());
		}
		this.unionHash.get(id).add(keyStr);
	}
	
	public boolean isReverseUnion(Node snode, NodeItem item, Node target) {
        String lookup = new NodeGroupItemStr(snode, item, target, true).getStr();
        return this.getUnionKey(lookup) != null;
	}
	
	// rm item from unionHash
    // silent if item is not in unionHash
    public Integer getUnionKey(Node snode, NodeItem item, Node target) {
        String lookup1 = new NodeGroupItemStr(snode, item, target, true).getStr();
        String lookup2 = new NodeGroupItemStr(snode, item, target, false).getStr();

        Integer ret = this.getUnionKey(lookup1);
        if (ret == null) {
        	ret = this.getUnionKey(lookup2);
        }
        return ret;
    }
    
    public Integer getUnionKey(Node snode, PropertyItem item) {
        String lookup = new NodeGroupItemStr(snode, item).getStr();
        return this.getUnionKey(lookup);
    }
    
    public Integer getUnionKey(Node snode) {
        String lookup = new NodeGroupItemStr(snode).getStr();
        return this.getUnionKey(lookup);
    }
    
    /**
     * Get union key for an item
     * 	- negative if reverse flag
     *  - null if none
     * @param lookup
     * @return
     */
    private Integer getUnionKey(String lookup) {
        for (Integer key : this.unionHash.keySet()) {
            for (String i : this.unionHash.get(key)) {

                if (i.equals(lookup)) {
                	if (i.toLowerCase().endsWith("|true")) {
                		return -key;
                	} else {
                		return key;
                	}
                }
            }
        }
        return null;
    }
    
    // get union keys of snode and its props and nodeitems
    private ArrayList<Integer> getUnionKeyList(Node snode) {
    	ArrayList<Integer> ret = new ArrayList<Integer>();
        Integer u = this.getUnionKey(snode);
        if (u != null) {
            ret.add(u);
        }
        for (PropertyItem p : snode.getPropertyItems()) {
            u = this.getUnionKey(snode, p);
            if (u != null) {
                ret.add(u);
            }
        }
        for (NodeItem n : snode.getNodeItemList()) {
            for (Node t : n.getNodeList()) {
                u = this.getUnionKey(snode, n, t);
                if (u != null) {
                    ret.add(Math.abs(u));
                }
            }
        }
        return ret;
    }
	
    private void addToUnionMembershipHashes(int id, String parentEntryStr, Node snode, NodeItem nItem, Node target) {
    	this.addToUnionMembershipHashes(id, parentEntryStr, new NodeGroupItemStr(snode, nItem, target).getStr());
    }
    private void addToUnionMembershipHashes(int id, String parentEntryStr, Node snode, PropertyItem pItem) {
		this.addToUnionMembershipHashes(id, parentEntryStr, new NodeGroupItemStr(snode, pItem).getStr());
    }
    private void addToUnionMembershipHashes(int id, String parentEntryStr, Node snode) {
        this.addToUnionMembershipHashes(id, parentEntryStr, new NodeGroupItemStr(snode).getStr());
    }
    private void addToUnionMembershipHashes(int id, String parentEntryStr, String keyStr) {
		if (!this.tmpUnionMemberHash.containsKey(keyStr)) {
			this.tmpUnionMemberHash.put(keyStr, new ArrayList<Integer>());
		}
		this.tmpUnionMemberHash.get(keyStr).add(id);
		
        if (!this.tmpUnionParentHash.containsKey(keyStr)) {
            this.tmpUnionParentHash.put(keyStr, new HashMap<Integer,String>());
        }
        this.tmpUnionParentHash.get(keyStr).put(id, parentEntryStr);
	}
	
	private class DepthTuple implements Comparator {
		public int key;
		public int depth;
		public DepthTuple(int key, int depth) {
			this.key = key;
			this.depth= depth;
		}
		// deepest first
		public int compare(Object obj1, Object obj2) {
	       Integer p1 = ((DepthTuple) obj1).depth;
	       Integer p2 = ((DepthTuple) obj2).depth;

	       if (p1 > p2) {
	           return -1;
	       } else if (p1 < p2){
	           return 1;
	       } else {
	           return 0;
	       }
	    }
	}
	// expensive operation calculates all union memberships
	// not sure if this will be needed on java side
	// ported from js  aug 2020 Paul
    @SuppressWarnings({ "rawtypes", "unchecked" })
	public void updateUnionMemberships() {

        this.tmpUnionMemberHash = new HashMap<String, ArrayList<Integer>>();  // hash entry str (3 tuple, not 4) to a list of unions

        // loop through all unionHash entries
        for (Integer unionKey : this.unionHash.keySet()) {
            for (String entryStr : this.unionHash.get(unionKey)) {
                NodeGroupItemStr entry = new NodeGroupItemStr(entryStr, this);
            
                if (entry.getType() == PropertyItem.class) {
                    // propertyItem: easy add
                    this.addToUnionMembershipHashes(unionKey, entryStr, entry.getStr());
                } else {
                    // get list of nodes in the Union
                    ArrayList<Node> subgraphNodeList;
                    if (entry.getType() == Node.class) {
                        subgraphNodeList = this.getSubGraph(entry.getSnode(), new ArrayList<Node>());
                    } else {
                        // nodeItems

                        // get subgraph to add
                    	ArrayList<Node> stopList = new ArrayList<Node>();
                        if (entry.getReverseFlag() == false) {
                        	this.addToUnionMembershipHashes(unionKey, entryStr, entry.getStrNoRevFlag());
                        	stopList.add(entry.getSnode());
                            subgraphNodeList = this.getSubGraph(entry.getTarget(), stopList);
                        } else {
                        	stopList.add(entry.getTarget());
                            subgraphNodeList = this.getSubGraph(entry.getSnode(), stopList);
                        }
                    }

                    // add the nodes
                    for (Node subgraphNode : subgraphNodeList) {
                        // add the node
                        this.addToUnionMembershipHashes(unionKey, entryStr, subgraphNode);

                        // add its props
                        for (PropertyItem prop : subgraphNode.getReturnedPropertyItems()) {
                            this.addToUnionMembershipHashes(unionKey, entryStr, subgraphNode, prop);
                        }

                        // add its connected nodeItems
                        ArrayList<NodeItem> nodeItemList = subgraphNode.getNodeItemList();
                        for (NodeItem nodeItem : nodeItemList) {
                            ArrayList<Node> targetSNodes = nodeItem.getNodeList();
                            for (Node target : targetSNodes) {
                                // - don't need membershipList, collapse it below (in this function)
                                // - fix getUnionMembership  (document that "boss" is also a member)
                                // - fix get LegalUnions
                                this.addToUnionMembershipHashes(unionKey, entryStr, subgraphNode, nodeItem, target);
                            }
                        }
                    }
                }
            }
        }


        // rearrange so entry [0] is the parent and grandparents are later
        // instead of reimplementing a sort, use the DepthTuple object in an ArrayList
        for (String keyStr : this.tmpUnionMemberHash.keySet()) {
        	ArrayList<Integer> val = this.tmpUnionMemberHash.get(keyStr);
        	ArrayList tuples = new ArrayList();
        	// build an arrayList of DepthTuples
        	for (Integer v : val) {
        		tuples.add(new DepthTuple(v, this.getUnionDepth(v)));
        	}
        	Collections.sort(tuples);
        	// re-order val based on the order of sorted DepthTuples
        	val.clear();
        	for (Object t : tuples) {
        		val.add(((DepthTuple) t).key);
        	}
        }
    }

    private int getUnionDepth(int unionKey) {
        String firstEntryStr = this.unionHash.get(unionKey).get(0);
        return this.tmpUnionMemberHash.get(NodeGroupItemStr.rmRevFlag(firstEntryStr)).size();
    }
	
    /**
     * Get a list of unions to which this item belongs
     * @param snode
     * @param nItem
     * @param target
     * @return
     */
    public ArrayList<Integer> getUnionMembershipList(Node snode, NodeItem nItem, Node target) {
    	return this.tmpUnionMemberHash.get(new NodeGroupItemStr(snode, nItem, target).getStr());
    }
	public ArrayList<Integer> getUnionMembershipList(Node snode, PropertyItem pItem) {
		return this.tmpUnionMemberHash.get(new NodeGroupItemStr(snode, pItem).getStr());
    }
	public ArrayList<Integer> getUnionMembershipList(Node snode) {
		return this.tmpUnionMemberHash.get(new NodeGroupItemStr(snode).getStr());
    }
    
	/**
	 * Get int key of the most deeply-nested union to which this item belongs, or null
	 * @param snode
	 * @param nItem
	 * @param target
	 * @return
	 */
	public Integer getUnionMembership(Node snode, NodeItem nItem, Node target) {
        ArrayList<Integer> memberOfList = this.getUnionMembershipList(snode, nItem, target);
        return (memberOfList != null) ? memberOfList.get(0) : null;
    }
	public Integer getUnionMembership(Node snode, PropertyItem pItem) {
		ArrayList<Integer> memberOfList = this.getUnionMembershipList(snode, pItem);
        return (memberOfList != null) ? memberOfList.get(0) : null;    
    }
	public Integer getUnionMembership(Node snode) {
		ArrayList<Integer> memberOfList = this.getUnionMembershipList(snode);
        return (memberOfList != null) ? memberOfList.get(0) : null;    
    }
    
	/**
	 * Get unions this item could join
	 * @param snode
	 * @param nItem
	 * @param target
	 * @return
	 */
	 public ArrayList<Integer> getLegalUnions(Node snode, NodeItem nItem, Node target) {
	    	return this.getLegalUnions(this.getUnionKey(snode, nItem, target), new NodeGroupItemStr(snode, nItem, target));
	 }
	 public ArrayList<Integer> getLegalUnions(Node snode, PropertyItem pItem) {
		 return this.getLegalUnions(this.getUnionKey(snode, pItem), new NodeGroupItemStr(snode, pItem));
	 }
	 public ArrayList<Integer> getLegalUnions(Node snode) {
		 return this.getLegalUnions(this.getUnionKey(snode), new NodeGroupItemStr(snode));
	 }
	
	 private ArrayList<Integer> getLegalUnions(Integer key, NodeGroupItemStr uKeyStr) {
		 String keyStr = uKeyStr.getStr();
		 ArrayList<Integer> membershipList = this.tmpUnionMemberHash.get(keyStr);
		 Integer k = key != null ? Math.abs(key) : null;
		 if (membershipList.get(0) == k) {
			 membershipList.remove(0);
		 }
		 ArrayList<Integer> ret = new ArrayList<Integer>();
		 for (Integer unionKey : this.unionHash.keySet()) {
			 // get first member of union, and it's membership list
			 String firstMemberStr = NodeGroupItemStr.rmRevFlag(this.unionHash.get(unionKey).get(0));
			 ArrayList<Integer> firstMemberMembership = new ArrayList<Integer>(this.tmpUnionMemberHash.get(firstMemberStr));
			 // remove the member's union to be left with any other memberships
			 firstMemberMembership.remove(0);

			 // add to ret union non-key memberships match this union's first member's non-key memberships.
			 if (firstMemberMembership.size() == membershipList.size()) {
				 boolean same = true;
				 for (int i=0; i < membershipList.size(); i++) {
					 if (firstMemberMembership.get(i) != membershipList.get(i)) {
						 same = false;
						 break;
					 }
				 }
				 if (same) {
					 ret.add(unionKey);
				 }
			 }
		 }

		 // remove illegally connected unions for snodes or nodeitems
		 if (uKeyStr.getType() != PropertyItem.class) {
			 Node startNode;
			 ArrayList<Node> stopNodes = new ArrayList<Node>();

			 Node snode = uKeyStr.getSnode();
			 Node target = uKeyStr.getTarget();
			 if (uKeyStr.getType() == Node.class) {
				 // snode:  anything connected is illegal
				 startNode = snode;
			 } else {
				 // nodeItem:  anything downstream is illegal
				 if (uKeyStr.getReverseFlag()) {
					 startNode = snode;
					 stopNodes.add(target);
				 } else {
					 startNode = target;
					 stopNodes.add(snode);
				 }
			 }
			 ArrayList<Node> subgraph = this.getSubGraph(startNode, stopNodes);

			 // do the removal
			 ArrayList<Integer> illegals = new ArrayList<Integer>();
			 for (Node nd : subgraph) {
				 illegals.addAll(this.getUnionKeyList(nd));
			 }
			 for (Integer ill : illegals) {
				 // don't remove key.  It slips in if reverseFlag==true.
				 if (ill != key) {
					 int idx = ret.indexOf(ill);
					 if (idx > -1) {
						 ret.remove(idx);
					 }
				 }
			 }
		 }
		 return ret;
    }
	 
	/**
	 * Set isBindingReturned for all items in the nodegroup that match this one
	 * @param item
	 * @param val
	 */
	public void setBindingIsReturned(Returnable item, boolean val) {
		String bindingName = item.getBinding();
		for (Node snode : this.getNodeList()) {
			if (snode.getBinding() != null && snode.getBinding().equals(bindingName)) {
				snode.setIsBindingReturned(val);
			}
			for (PropertyItem prop : snode.getPropertyItems()) {
				if (prop.getBinding() != null && prop.getBinding().equals(bindingName)) {
					prop.setIsBindingReturned(val);
				}
			}
		}
	}
    
	public void addOneNode(Node curr, Node existingNode, String linkFromNewUri, String linkToNewUri) throws Exception  {
		this.addOneNode(curr, existingNode, linkFromNewUri, linkToNewUri, true);
	}

	/**
	 * 
	 * @param curr
	 * @param existingNode
	 * @param linkFromNewUri
	 * @param linkToNewUri
	 * @param legalizeSparqlIDs - boolean allows override of sparqlID fixing if we're loading a json
	 * @throws Exception
	 */
	public void addOneNode(Node curr, Node existingNode, String linkFromNewUri, String linkToNewUri, boolean legalizeSparqlIDs) throws Exception  {


		// add the node to the nodegroup control structure..
		this.nodes.add(curr);
		
		if (legalizeSparqlIDs) {
			this.legalizeSparqlIDs(curr);
		}
				
		this.idToNodeHash.put(curr.getSparqlID(), curr);
		// set up the connection info so this node participates in the graph
		if(linkFromNewUri != null && linkFromNewUri != ""){
			curr.setConnection(existingNode, linkFromNewUri);
		}
		else if(linkToNewUri != null && linkToNewUri != ""){
			existingNode.setConnection(curr, linkToNewUri);
		}
		else{
			//no op
		}
		
	}

	private void legalizeSparqlIDs(Node node) {
		String ID = node.getSparqlID();
		HashSet<String> nameHash = this.getAllVariableNames(node);
		if(nameHash.contains(ID)){	// this name was already used. 
			ID = BelmontUtil.generateSparqlID(ID, nameHash);
			node.setSparqlID(ID);	// update it. 
		}
		
		String binding = node.getBinding();
		if (binding != null && nameHash.contains(binding))  {
			binding = BelmontUtil.generateSparqlID(binding, nameHash);
			node.setBinding(binding);	// update it. 
		}
		// check the properties...
		ArrayList<PropertyItem> props = node.getReturnedPropertyItems();
		for(int i = 0; i < props.size(); i += 1){
			PropertyItem pItem = props.get(i);
			nameHash = this.getAllVariableNames(node, pItem);
			String pID = pItem.getSparqlID();
			if(nameHash.contains(pID)){
				pID = BelmontUtil.generateSparqlID(pID, nameHash);
				pItem.setSparqlID(pID);
			}
			binding = pItem.getBinding();
			if(binding != null && nameHash.contains(binding)){
				binding = BelmontUtil.generateSparqlID(binding, nameHash);
				pItem.setBinding(binding);
			}
		}
	}

	
	/**
	 * 
	 * @param uri
	 * @return ArrayList of Nodes which are of class uri
	 */
	public ArrayList<Node> getNodesByURI(String uri) {
		// get all nodes with the given uri
		ArrayList<Node> ret = new ArrayList<Node>();

		for (int i = 0; i < this.nodes.size(); i++) {
			if (this.nodes.get(i).getUri().equals(uri)) {
				ret.add(this.nodes.get(i));
			}
		}
		return ret;
	}
	
	/**
	 * 
	 * @param uri
	 * @param oInfo
	 * @return ArrayList of Nodes which are of class uri or any of its subclasses
	 */
	public ArrayList<Node> getNodesBySuperclassURI(String uri, OntologyInfo oInfo) {
		// get all nodes with the given uri
		ArrayList<Node> ret = new ArrayList<Node>();

		// get all subclasses
		ArrayList<String> classes = new ArrayList<String>();
		classes.add(uri);
		classes.addAll(oInfo.getSubclassNames(uri));
		
		// for each class / sub-class
		for (int i=0; i < classes.size(); i++) {
			// get all nodes
			ArrayList<Node> c = this.getNodesByURI(classes.get(i));
			// push node if it isn't already in ret
			for (int j=0; j < c.size(); j++) {
				if (ret.indexOf(c.get(j)) == -1) {
					ret.add(c.get(j));
				}
			}
		}
		
		return ret;
	}
	
	/**
	 * Look up a node by sparqlId
	 * @param sparqlId - optionally starts with "?"
	 * @return - index or -1
	 */
	public int getNodeIndexBySparqlID(String sparqlId) {
		String lookupId = (sparqlId.charAt(0) == '?') ? sparqlId : "?" + sparqlId;
		
		// look up a node by ID and return it. 
		int size = nodes.size();
		for(int i = 0; i < size; i += 1){
			// can we find it by name?
			if(this.nodes.get(i).getSparqlID().equals(lookupId)){   // this was "==" but that failed in some cases...
				return i;
			}
		}
		return -1;
	}
	
	public Node getNodeBySparqlID(String sparqlId) {
		String lookupId = (sparqlId.charAt(0) == '?') ? sparqlId : "?" + sparqlId;
		return this.idToNodeHash.get(lookupId);

	}
	
	public Node getNode(int i) {
		return this.nodes.get(i);
	}
	
	public PropertyItem getPropertyItemBySparqlID(String sparqlId){
		// finds the given propertyItem by assigned sparql ID.
		// if no matches are found, it returns a null... 
				
		PropertyItem retval = null;
		
		for(Node nc : this.nodes){
			PropertyItem candidate = nc.getPropertyItemBySparqlID(sparqlId);
			if(candidate != null){
				retval = candidate;
				break;			// always in the last place we look.
			}
		}
		// return it. 
		return retval;
	}

	/**
	 * Unset every isReturned in the nodegroup
	 */
	public void unsetAllReturns() throws Exception {
		this.unsetAllReturns(null);
		this.clearOrderBy();
	}
	
	/**
	 * Unset every isReturned in the nodegroup...
	 * @param exceptThis - but not this one
	 */
	public void unsetAllReturns(Returnable exceptThis) throws Exception {
		// get returned items
		ArrayList<Returnable> items = this.getReturnedItems();
		
		// unset
		for(Returnable item : items) {
			if (item != exceptThis) {
				this.setIsReturned(item, false);
			
				item.setIsTypeReturned(false);
				item.setIsBindingReturned(false);
			}
		}
	}
	
	/**
	 * Get all returned variables
	 * @return
	 */
	
	public ArrayList<String> getReturnedSparqlIDs() {
		
		// get returned items
		ArrayList<Returnable> items = this.getReturnedItems();
		ArrayList<String> ret = new ArrayList<String>();
		String id;
		
		// build list of sparql ids
		for(Returnable item : items) {
			if (item.getIsReturned()) {
				id = item.getSparqlID();
				if (!ret.contains(id)) {
					ret.add(id);
				}
			}
			if (item.getIsTypeReturned()) {
				id = item.getTypeSparqlID();
				if (!ret.contains(id)) {
					ret.add(id);
				}
			}
			if (item.getIsBindingReturned()) {
				id = item.getBinding();
				if (!ret.contains(id)) {
					ret.add(id);
				}
			}
		}
		return ret;
	}
	
	
	public ArrayList<Returnable> getReturnedItems() {
		ArrayList<Returnable> ret = new ArrayList<Returnable>();
		for(Node n : this.getOrderedNodeList()) {
			// check if node URI is returned
			if (n.hasAnyReturn()) {
				ret.add(n);
			} 
			
			ArrayList<PropertyItem> retPropItems = n.getReturnedPropertyItems();
			for (PropertyItem p : retPropItems) {
				ret.add(p);
			}
		}
		return ret;
	}
	
	/**
	 * Get the keyStr of the object at the branch of this snode's union
	 * @param snode
	 * @return
	 */
	private String getUnionParentStr(Node snode) {
		Integer unionKey = this.getUnionMembership(snode);
		if (unionKey == null) {
			return null;
		} else {
			String keyStr = new NodeGroupItemStr(snode).toString();
			return this.tmpUnionParentHash.get(keyStr).get(unionKey);
		}
	}
	
	/**
	 * Get the keyStr of the object at the branch of this prop's union
	 * @param snode
	 * @return
	 */
	private String getUnionParentStr(Node snode, PropertyItem pItem) {
		Integer unionKey = this.getUnionMembership(snode, pItem);
		if (unionKey == null) {
			return null;
		} else {
			String keyStr = new NodeGroupItemStr(snode, pItem).toString();
			return this.tmpUnionParentHash.get(keyStr).get(unionKey);
		}
	}
	
	
	/**
	 * Get all variable names in the nodegroup
	 * @return
	 */
	public HashSet<String> getAllVariableNames() {
		return this.getAllVariableNames((Returnable) null, (Integer) null, (String) null);
	}
	
	public HashSet<String> getAllVariableNames(Node targetSNode) {
		this.updateUnionMemberships();

		Integer targetUnion = this.getUnionMembership(targetSNode);
		String targetParentStr = this.getUnionParentStr(targetSNode);
		return this.getAllVariableNames(targetSNode, targetUnion, targetParentStr);
	}
	
	public HashSet<String> getAllVariableNames(Node targetSNode, PropertyItem targetPItem) {
		this.updateUnionMemberships();

		Integer targetUnion = this.getUnionMembership(targetSNode, targetPItem);
		String targetParentStr = this.getUnionParentStr(targetSNode, targetPItem);
		return this.getAllVariableNames(targetPItem, targetUnion, targetParentStr);
	}
	
	
	/**
	 * Get all variable names in the nodegroup except those in same union and different parent as targetItem
	 * @param targetItem
	 * @return
	 */
	private HashSet<String> getAllVariableNames(Returnable target, Integer targetUnion, String targetParentStr) {
		
		HashSet<String> ret = new HashSet<String>();
		
		for (Node snode : this.nodes) {
			Integer snodeUnion = this.getUnionMembership(snode);
			String snodeParentStr = this.getUnionParentStr(snode);
			// if different union or no union or same union parent
			if (snodeUnion != targetUnion || snodeParentStr == null || targetParentStr.equals(snodeParentStr)) {
				if (snode != target ) {
					ret.add(snode.getSparqlID());
				
					if (snode.getBinding() != null) {
						ret.add(snode.getBinding());
					}
					if (snode.getIsTypeReturned()) {
						ret.add(snode.getTypeSparqlID());
					}
				}
			}
			
			for (PropertyItem prop : snode.getPropertyItems()) {
				if (prop != target) {
					Integer propUnion = this.getUnionMembership(snode, prop);
					String propParentStr = this.getUnionParentStr(snode, prop);
					// if different union or no union or same union parent
					if (propUnion != targetUnion || propParentStr == null || targetParentStr.equals(propParentStr)) {
						if (prop.getSparqlID() != null) {
							ret.add(prop.getSparqlID());
						}
						if (prop.getBinding() != null) {
							ret.add(prop.getBinding());
						}
					}
				}
			}
		}
		return ret;
	}
	
	
	/**
	 * Find next node in path
	 * @param startNode
	 * @param t
	 * @return
	 * @throws Exception if there isn't exactly one path
	 */
	private Node getNode(Node startNode, Triple t) throws Exception {
		String pred = t.getPredicate();
		
		if (t.getSubject().equals(startNode.getFullUriName())) {
			
			// outgoing predicate
			NodeItem ni =  startNode.getNodeItem(pred);
			
			// check it
			if (ni == null) {
				throw new Exception ("Error following path. " + startNode.getSparqlID() + " does not have object property: " + pred);
			} else if (ni.getNodeList().size() > 1) {
				throw new Exception ("Error following path. " + startNode.getSparqlID() + " has more than one path along: " + pred);
			} else if (ni.getNodeList().size() < 1) {
				throw new Exception ("Error following path. " + startNode.getSparqlID() + " has more nothing connected to: " + pred);
			}
			return ni.getNodeList().get(0);
			
		} else if (t.getObject().equals(startNode.getFullUriName())) {
			
			// incoming predicate
			ArrayList<NodeItem> niList = this.getConnectingNodeItems(startNode);
			NodeItem ni = null;
			for (NodeItem n : niList) {
				if (n.getUriConnectBy().equals(pred)) {
					if (ni != null) {
						throw new Exception ("Error following path. " + startNode.getSparqlID() + " has multiple incoming: " + pred);
					} else {
						ni = n;
					}
				}
			}
			
			// check it
			if (ni == null) {
				throw new Exception ("Error following path. " + startNode.getSparqlID() + " does not have incoming property: " + pred);
			} 
			return this.getNodeItemParentSNode(ni);
			
		} else {
			
			// got lost
			throw new Exception ("Error following path. " + startNode.getSparqlID() + " can't find incoming or outgoing: " + pred);
		}
	}
	
	/**
	 * Follow a path to a node
	 * @param startNode - start here
	 * @param path - follow this
	 * @return final node in path
	 * @throws Exception - if it gets confused at a branch
	 */
	public Node followPathToNode(Node startNode, OntologyPath path) throws Exception {
		Node node = startNode;
		for (Triple t : path.getTripleList()) {
			node = this.getNode(node, t);
		}
		return node;
	}
	
	/**
	 * Find Node or PropertyItem with sparqlID==id anywhere in nodegroup, otherwise null
	 * @param id
	 * @return
	 */
	public Returnable getItemBySparqlID(String id) {
		String search = id;
		if (!search.startsWith("?"))
			search = "?" + search;
		
        for (Node n : this.nodes) {
            if (n.getSparqlID().equals(search)) {
                return n;
            }
            
            Returnable item = n.getPropertyItemBySparqlID(search);
            if (item != null) {
                return item;
            }
        }
		return null;
    }
	
	/**
	 * Find all items in nodegroup with this uri
	 * @param uri
	 * @return
	 */
	public ArrayList<PropertyItem> getPropertyItems(String uri) {
		ArrayList<PropertyItem> ret = new ArrayList<PropertyItem>();
		
		for (Node n : this.nodes) {
           
            PropertyItem pItem = n.getPropertyByURIRelation(uri);
            if (pItem != null) {
            	 ret.add(pItem);
            }
            
        }
		return ret;
	}
	
	public ArrayList<NodeItem> getNodeItems(String uri) {
		ArrayList<NodeItem> ret = new ArrayList<NodeItem>();
		
		for (Node n : this.nodes) {
            
            NodeItem nItem = n.getNodeItem(uri);
            if (nItem != null) {
            	 ret.add(nItem);
            }
        }
		return ret;
	}
	
	/**
	 * Generate vanilla SELECT
	 * @return
	 * @throws Exception
	 */
	public String generateSparqlSelect() throws Exception {
		return this.generateSparql(AutoGeneratedQueryTypes.QUERY_DISTINCT, false, -1, null);
	}
	
	public String generateSparql(AutoGeneratedQueryTypes qt, Boolean allPropertiesOptional, Integer limitOverride, Returnable targetObj) throws Exception {
		return this.generateSparql(qt, allPropertiesOptional, limitOverride, targetObj, false);
	}

	public String generateSparql(AutoGeneratedQueryTypes qt, Boolean allPropertiesOptional, Integer limitOverride, Returnable targetObj, Boolean keepTargetConstraints) throws Exception{
		//
		// queryType:
		//     QUERY_DISTINCT - select distinct.   Use of targetObj is undefined.
		//     QUERY_CONSTRAINT - select distinct values for a specific node or prop item (targetObj) after removing any existing constraints
		//     QUERY_COUNT - count results.  If targetObj the use that as the only object of "select distinct".  
		//     QUERY_CONSTRUCT - construct query
		//
		// limitOverride - if > -1, then override this.limit     DEPRECATED
		//
		// targetObj - if not (null/undefined/0/false/'') then it should be a SemanticNode or a PropertyItem
		//    QUERY_CONSTRAINT - must be set.   Return all legal values for this.   Remove constraints.
        //    QUERY_COUNT - if set, count results of entire query but only this distinct return value
		//
		// keepTargetConstraints - keep constraints on the target object 
		//
		// Error handling: For each error, inserts a comment at the beginning.
		//    #Error: explanation
		
		this.buildPrefixHash();
		this.updateUnionMemberships();
		
		String retval = "";
		String tab = SparqlToXUtils.tabIndent("");
		
		if (nodes.size() == 0) {
			return retval;
		}
		
		StringBuilder sparql = new StringBuilder();
		sparql.append(this.generateSparqlPrefix());
		
		if (qt.equals(AutoGeneratedQueryTypes.QUERY_COUNT)) {
			sparql.append(this.generateCountHeader(tab));
		}
		
		// SELECT DISTINCT or CONSTRUCT clause
		AutoGeneratedQueryTypes clauseType;
		switch (qt) {
		case QUERY_CONSTRUCT:
			sparql.append(this.generateSelectDistinctClause(tab));
			clauseType = AutoGeneratedQueryTypes.QUERY_CONSTRUCT_WHERE;
			break;
		default:
			clauseType = qt;
			sparql.append(this.generateSelectDistinctClause(qt, targetObj, tab));
		}
		
		// add the WHERE clause unless it is a COUNT query (already added the WHERE on the outer layer...above)
		if (! qt.equals(AutoGeneratedQueryTypes.QUERY_COUNT)) {
			sparql.append(SparqlToXUtils.generateSparqlFromOrUsing(tab, "FROM", this.conn, this.oInfo));
		}
		
		
		
		sparql.append(" where {\n");
		
		ArrayList<Node> doneNodes = new ArrayList<Node>();
		ArrayList<Integer> doneUnions = new ArrayList<Integer>();
		Node headNode = this.getNextHeadNode(doneNodes);
		while (headNode != null) {
		
			Integer unionKey = this.getSubGraphUnionKey(headNode);
			if (unionKey == null ) {
				sparql.append(this.generateSparqlSubgraphClausesNode(	clauseType, 
																		headNode, 
																		null, null,   // skip nodeItem.  Null means do them all.
																		keepTargetConstraints ? null : targetObj, 
																		doneNodes, doneUnions,
																		tab));
			} else {
				sparql.append(this.generateSparqlSubgraphClausesUnion(	clauseType, unionKey, keepTargetConstraints ? null : targetObj, doneNodes, doneUnions, tab));
			}
			headNode = this.getNextHeadNode(doneNodes);
		}
		
		sparql.append("}\n");
		
		sparql.append(this.generateOrderByClause());
		sparql.append(this.generateLimitClause(limitOverride));
		sparql.append(this.generateOffsetClause());
		
		if (qt.equals(AutoGeneratedQueryTypes.QUERY_COUNT)) {
			sparql.append(this.generateCountFooter(tab));
		}
		
		retval = sparql.toString();
		
		return retval;
	}
	
	private String generateSelectDistinctClause(AutoGeneratedQueryTypes qt, Returnable targetObj, String tab) throws Exception {
		StringBuffer sparql = new StringBuffer();
		
		sparql.append("select distinct");
		int lastLen = sparql.length();
		
		if (targetObj != null) {
			// QUERY_CONSTRAINT or QUERY_COUNT or anything that set targetObj:  simple
			// only the targetObj is returned
			
			// Virtuoso bug or hard-to-understand equality of floating point.
			// Solution is from: https://stackoverflow.com/questions/38371049/sparql-distinct-gives-duplicates-in-virtuoso
			// -Paul 11/3/2017
			if (targetObj.getValueType() == XSDSupportedType.FLOAT) {
				sparql.append(" " + " XMLSchema:float(str(" + targetObj.getSparqlID() + "))");
			} else {
				sparql.append(" " + targetObj.getSparqlID());
			}
		}
		else {
			if (qt.equals(AutoGeneratedQueryTypes.QUERY_CONSTRAINT)) {
				throw new Exception("Can not generate a filter constraint query with no target object");
			}
			ArrayList<String> ids = this.getReturnedSparqlIDs();
			for (String id : ids) {
				sparql.append(" " + id);
			}
		}
		
		// if there are no return values, it is an error. Prepend "#Error" to
		// the SPARQL
		if (sparql.length() == lastLen) {
			throw new NoValidSparqlException("No values selected to return");
		}
		
		return sparql.toString();
	}

	private String generateSelectDistinctClause(String tab) throws Exception {
		StringBuffer sparql = new StringBuffer();
				
		
		// Construct
		sparql.append("CONSTRUCT {\n");
				
		ArrayList<Node> doneNodes  = new ArrayList<Node>();
		ArrayList<Integer> doneUnions = new ArrayList<Integer>();
		Node headNode = this.getNextHeadNode(doneNodes);

		while (headNode != null) {
			
			sparql.append(this.generateSparqlSubgraphClausesNode(	AutoGeneratedQueryTypes.QUERY_CONSTRUCT, 
																	headNode, 
																	null, null,    // skip nodeItem.  Null means do them all.
																	null,    // no targetObj
																	doneNodes, doneUnions,
																	tab));
			headNode = this.getNextHeadNode(doneNodes);
		}
		
		sparql.append("\n}");
		
		return sparql.toString();
	}
	
	private String generateCountHeader(String tab) throws Exception {
		StringBuffer sparql = new StringBuffer();
		
		sparql.append("SELECT (COUNT(*) as ?count) \n");
		sparql.append(SparqlToXUtils.generateSparqlFromOrUsing(tab, "FROM", this.conn, this.oInfo));
		sparql.append(" { \n");
		
		return sparql.toString();
	}
	
	private String generateCountFooter(String tab){
		return "\n}";
	}
	
	private String generateOffsetClause() {
		if (this.offset != 0) {
			return " OFFSET " + String.valueOf(this.offset) + "\n";
		} else {
			return "";
		}
	}
	
	private String generateOrderByClause() throws Exception {
		this.validateOrderBy();
		
		if (this.orderBy.size() > 0) {
			StringBuffer ret = new StringBuffer();
			ret.append("ORDER BY");
			for (OrderElement e : this.orderBy) {
				ret.append(" ");
				ret.append(e.toSparql());
			}
			return ret.toString();
		} else {
			return "";
		}
	}
	
	/**
     *  limitOverride If non-null then override this.limit  DEPRECATED
     */
	private String generateLimitClause(Integer limitOverride) {
        int limit = (limitOverride != null && limitOverride > -1) ? limitOverride : this.limit;
        if (limit > 0) {
			return " LIMIT " + String.valueOf(limit); 
		} 
        else 
		{
            return "";
    	}
    }


	
	/**
	 * FROM clause logic
	 * Generates FROM clause if this.conn has
	 *     - exactly 1 data connection
	 */
	private String generateSparqlWithClause(String tab) {
		
		// do nothing if no conn
		if (this.conn == null) return "";
		
		// multiple ServerURLs is not implemented
		if (this.conn.getDataInterfaceCount() < 1) {
			throw new Error("Can not generate a WITH clause when there is no data connection");
		}
		if (this.conn.getDataInterfaceCount() > 1) {
			throw new Error("Can not generate a WITH clause when there are multiple data connections");
		}
		
		return tab + " WITH <" + this.conn.getDataInterface(0).getGraph() + "> ";
	}
	
	@Deprecated
	public String generateSparqlConstruct(boolean unused) throws Exception {
		return this.generateSparqlConstruct();
	}
	
	public String generateSparqlConstruct() throws Exception {
		return this.generateSparql(AutoGeneratedQueryTypes.QUERY_CONSTRUCT, false, -1, null, false);
	}

	public String generateSparqlConstructDELETE_ME() throws Exception {
	
		this.buildPrefixHash();
		
		String tab = SparqlToXUtils.tabIndent("");
		StringBuilder sparql = new StringBuilder();
		sparql.append(this.generateSparqlPrefix());
		
		
		// Construct
		sparql.append("CONSTRUCT {\n");
				
		ArrayList<Node> doneNodes  = new ArrayList<Node>();
		ArrayList<Integer> doneUnions = new ArrayList<Integer>();
		Node headNode = this.getNextHeadNode(doneNodes);

		while (headNode != null) {
			
			sparql.append(this.generateSparqlSubgraphClausesNode(	AutoGeneratedQueryTypes.QUERY_CONSTRUCT, 
																	headNode, 
																	null, null,    // skip nodeItem.  Null means do them all.
																	null,    // no targetObj
																	doneNodes, doneUnions,
																	tab));
			headNode = this.getNextHeadNode(doneNodes);
		}
		
		sparql.append("\n}");
		
		// From
		sparql.append(SparqlToXUtils.generateSparqlFromOrUsing("", "FROM", this.conn, this.oInfo));
		
		// Where
		sparql.append("\nWHERE {\n");

		
		doneNodes = new ArrayList<Node>();
		doneUnions = new ArrayList<Integer>();
		headNode = this.getNextHeadNode(doneNodes);
		while (headNode != null) {
		
			sparql.append(this.generateSparqlSubgraphClausesNode(	AutoGeneratedQueryTypes.QUERY_CONSTRUCT_WHERE, 
																	headNode, 
																	null, null,   // skip nodeItem.  Null means do them all.
																	null,         // no targetObj
																	doneNodes, doneUnions, 
																	tab));
			headNode = this.getNextHeadNode(doneNodes);
		}
		
		sparql.append("}\n");
		
		return sparql.toString();
	}
	
	public String generateSparqlAsk() throws Exception {
		
		this.buildPrefixHash();
		
		// generate a sparql ask statement
		String footer = "";
		String tab = SparqlToXUtils.tabIndent("");
		
		ArrayList<Node> doneNodes = new ArrayList<Node>();
		ArrayList<Integer> doneUnions = new ArrayList<Integer>();
		Node headNode = this.getNextHeadNode(doneNodes);
		while (headNode != null) {
			footer += this.generateSparqlSubgraphClausesNode(AutoGeneratedQueryTypes.QUERY_CONSTRUCT_WHERE, headNode, null, null, null, doneNodes, doneUnions, tab);
			headNode = this.getNextHeadNode(doneNodes);
		}
		
		
		String retval = this.generateSparqlPrefix() + "ask  " + SparqlToXUtils.generateSparqlFromOrUsing(tab, "FROM", this.conn, this.oInfo) +   "\n{\n" + footer + "\n}";
 
		return retval;
	}
	/**
	 * Top-level subgraph SPARQL generator
	 * @param queryType
	 * @param snode
	 * @param skipNodeItem nodeItem to skip
	 * @param skipNodeTarget target snode to skip
	 * @param targetObj - target of FILTER queries
	 * @param doneNodes - nodes to skip
	 * @param tab - text TAB
	 * @return
	 * @throws Exception
	 */
	
	private String generateSparqlSubgraphClausesNode(AutoGeneratedQueryTypes queryType, Node snode, NodeItem skipNodeItem, Node skipNodeTarget, Returnable targetObj, ArrayList<Node> doneNodes, ArrayList<Integer> doneUnions, String tab) throws Exception  {
		StringBuilder sparql = new StringBuilder();
		
		String QUERY_CONSTRUCT_FOR_INSTANCE_MANIPULATION_POSTFIX = "___QCfIMP";
		String SPARQLID_BINDING_TAG = "<@Original-SparqlId>";
		
		// check to see if this node has already been processed. 
		if(doneNodes.contains(snode)){
			// nothing to do.
			return sparql.toString();
		}
		else{
			doneNodes.add(snode);
		}
				
		// added for deletions
		if (queryType == AutoGeneratedQueryTypes.QUERY_DELETE_WHERE && snode.getDeletionMode() != NodeDeletionTypes.NO_DELETE){
			sparql.append(this.generateNodeDeletionSparql(snode, true));
		}
		
		// This is the type-constraining statement for any type that needs
		// NOTE: this is at the top due to a Virtuoso bug
		sparql.append(this.generateSparqlTypeClause(snode, tab, queryType));
		//       If the first prop is optional and nothing matches then the whole query fails.
				
		// add binding unless it's a CONSTRUCT
		if (snode.getBinding() != null && queryType != AutoGeneratedQueryTypes.QUERY_CONSTRUCT) {
			sparql.append(tab + "BIND(" + snode.getSparqlID() + " as " +  snode.getBinding() + ") .\n" );
		}
		
		// PropItems: generate sparql for property and constraints
		// for(PropertyItem prop : snode.getReturnedPropertyItems()){
		for(PropertyItem prop : snode.getPropsForSparql(targetObj, queryType)){	
			Integer unionKey = this.getUnionKey(snode, prop);
			
			if (unionKey == null || queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT) {
				sparql.append( this.generateSparqlSubgraphClausesPropItem(queryType, snode, prop, targetObj, tab) );
			} else if (! doneUnions.contains(unionKey)) {
				sparql.append( this.generateSparqlSubgraphClausesUnion(queryType, unionKey, targetObj, doneNodes, doneUnions, tab) );
			}
		}
		
		// add value constraints...
		String constraintStr = snode.getValueConstraintStr();
		if(constraintStr != null && ! constraintStr.isEmpty()) {
			// add unless this is a constraint query on the target object
			if((queryType != AutoGeneratedQueryTypes.QUERY_CONSTRUCT)  && (queryType != AutoGeneratedQueryTypes.QUERY_CONSTRAINT || snode != targetObj)){
				sparql.append(tab + constraintStr + " . \n");
			}
		}

		// recursive process of NodeItem subtree  
		for(NodeItem nItem : snode.getNodeItemList()) {
			
			// each nItem might point to multiple children
			for(Node targetNode : nItem.getNodeList()) {
				if (nItem != skipNodeItem || targetNode != skipNodeTarget) {
					Integer unionKey = this.getUnionKey(snode, nItem, targetNode);
					if (unionKey == null || queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT) {
						sparql.append(this.generateSparqlSubgraphClausesNodeItem(queryType, false, snode, nItem, targetNode, targetObj, doneNodes, doneUnions, tab));
					} else if (unionKey >= 0) {
						sparql.append(this.generateSparqlSubgraphClausesUnion(queryType, unionKey, targetObj, doneNodes, doneUnions, tab));
					} else {
						//throw new Exception("SPARQL-generation is confused at reversed UNION nodeItem " + snode.getBindingOrSparqlID()  + "->" + targetNode.getBindingOrSparqlID());
					}
				}
			}
		}
		
		// Recursively process incoming nItems
		for(NodeItem nItem : this.getConnectingNodeItems(snode)) {   
			if (nItem != skipNodeItem || snode != skipNodeTarget) {
				Node incomingSNode = this.getNodeItemParentSNode(nItem); 
				Integer unionKey = this.getUnionKey(incomingSNode, nItem, snode);
				if (unionKey == null || queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT) {
					sparql.append(
							this.generateSparqlSubgraphClausesNodeItem(queryType, true, incomingSNode, nItem, snode, targetObj, doneNodes, doneUnions, tab )
							);
				} else if (unionKey >= 0) {		
					//throw new Exception("SPARQL-generation is confused at non-reversed UNION nodeItem ...
				} else {
					sparql.append(this.generateSparqlSubgraphClausesUnion(queryType, -unionKey, targetObj, doneNodes, doneUnions, tab));
				}
			}
		}
		
		return sparql.toString();
	}

	private String generateSparqlSubgraphClausesUnion(AutoGeneratedQueryTypes queryType, Integer unionKey, Returnable targetObj, ArrayList<Node> doneNodes, ArrayList<Integer> doneUnions, String tab) throws Exception {
		StringBuffer sparql = new StringBuffer();
		
		if (doneUnions.contains(unionKey)) {
			return "";
		} else {
			doneUnions.add(unionKey);
		}
		
		for (String itemStr : this.unionHash.get(unionKey)) {
			NodeGroupItemStr keyStr = new NodeGroupItemStr(itemStr, this);
			if (sparql.length() > 0) {
				sparql.append(tab + " UNION \n");
			}
			sparql.append(tab + "{\n");
			
			if (keyStr.getType() == Node.class) {
				tab = SparqlToXUtils.tabIndent(tab);
				sparql.append(this.generateSparqlSubgraphClausesNode(queryType, keyStr.getSnode(), null, null, targetObj, doneNodes, doneUnions, tab));
				tab = SparqlToXUtils.tabOutdent(tab);
			} else if (keyStr.getType() == NodeItem.class) {
				tab = SparqlToXUtils.tabIndent(tab);
				sparql.append(this.generateSparqlSubgraphClausesNodeItem(queryType, keyStr.getReverseFlag(), keyStr.getSnode(), keyStr.getnItem(), keyStr.getTarget(), targetObj, doneNodes, doneUnions, tab));
				tab = SparqlToXUtils.tabOutdent(tab);
			} else { // prop
				tab = SparqlToXUtils.tabIndent(tab);
				sparql.append(this.generateSparqlSubgraphClausesPropItem(queryType, keyStr.getSnode(), keyStr.getpItem(), targetObj, tab));
						
				tab = SparqlToXUtils.tabOutdent(tab);
			}
			
			sparql.append(tab + "}\n");
		}
		
		
		return sparql.toString();
	}
	
	/**
	 * 
	 * @param queryType
	 * @param incomingFlag - recursion should proceed from the nItem's parent SNode
	 * @param snode
	 * @param nItem
	 * @param targetNode
	 * @param targetObj
	 * @param doneNodes
	 * @param tab
	 * @return
	 * @throws Exception
	 */
	private String generateSparqlSubgraphClausesNodeItem(AutoGeneratedQueryTypes queryType, boolean incomingFlag, Node snode, NodeItem nItem, Node targetNode, Returnable targetObj, ArrayList<Node> doneNodes, ArrayList<Integer> doneUnions, String tab) throws Exception {
		boolean blockFlag = false;
		StringBuilder sparql = new StringBuilder();
		
		// open optional block
		if (incomingFlag) {
			if (nItem.getOptionalMinus(targetNode) == NodeItem.OPTIONAL_REVERSE && nItem.getNodeList().size() > 0 ) {
				sparql.append(tab + "optional {\n");
				tab = SparqlToXUtils.tabIndent(tab);
				blockFlag = true;
			} else if (nItem.getOptionalMinus(targetNode) == NodeItem.MINUS_REVERSE && nItem.getNodeList().size() > 0  && queryType != AutoGeneratedQueryTypes.QUERY_CONSTRUCT) {
				sparql.append(tab + "minus {\n");
				tab = SparqlToXUtils.tabIndent(tab);
				blockFlag = true;
			}
		} else {
			if(nItem.getOptionalMinus(targetNode) == NodeItem.OPTIONAL_TRUE && nItem.getNodeList().size() > 0 && queryType != AutoGeneratedQueryTypes.QUERY_CONSTRUCT){
				sparql.append(tab + "optional {\n");
				tab = SparqlToXUtils.tabIndent(tab);
				blockFlag = true;
			} else if(nItem.getOptionalMinus(targetNode) == NodeItem.MINUS_TRUE && nItem.getNodeList().size() > 0 && queryType != AutoGeneratedQueryTypes.QUERY_CONSTRUCT){
				sparql.append(tab + "minus {\n");
				tab = SparqlToXUtils.tabIndent(tab);
				blockFlag = true;
			}
		}

		String predStr;
		// prepare propStr for nodeItem connection clause
		
		// get subPropNames or null for IDK
		HashSet<String> subPropNames = (this.oInfo == null) ? null : this.oInfo.inferSubPropertyNames(nItem.getUriConnectBy(), snode.getUri());
		
		/**
		// Virtuoso can't handle subPropertyOf:
		// It is a messy presumption for sparql.
		// For now, if there's no oInfo, there are no sub-properties.
		
		if (subPropNames == null) {
			// don't know if there are sub-props
			String predVarName = SparqlToXUtils.safeSparqlVar(snode.getSparqlID() + "_" + nItem.getUriConnectBy()+ " " + targetNode.getSparqlID());
			
			if (queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT) {
				// variable w/o qualifier
				sparql.append(tab + snode.getBindingOrSparqlID() + " " + predVarName + " " + targetNode.getBindingOrSparqlID() + " .\n");

			} else if (	queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT_WHERE) {
				// variable with qualifier
				
				// Apparently SPARQL will not accept a predicate variable name with a qualifier, e.g. ?pred+ or ?(?pred)+
				// It isn't clear that this makes much sense anyway.
				// So this line is removed:
				//      predStr = this.applyQualifier(varName, nItem.getQualifier(targetNode));
				// And varName is used plain as long as there's no qualifier.
				// Exception if there is a qualifier
				if (!nItem.getQualifier(targetNode).isEmpty()) {
					throw new Exception("SemTK can't generate clause for property with subproperties and a qualifier: " + nItem.getUriConnectBy() + " " +  nItem.getQualifier(targetNode));
				}
				sparql.append(tab + snode.getBindingOrSparqlID() + " " + predVarName + " " + targetNode.getSparqlID() + " .\n");
				
				// constraint on the props variable
				sparql.append(tab + predVarName + " rdfs:subPropertyOf* " +  this.applyPrefixing(nItem.getUriConnectBy()) + " .\n");
				
				
			} else {
				if (!nItem.getQualifier(targetNode).isEmpty()) {
					throw new Exception("SemTK can't generate clause for property with subproperties and a qualifier: " + nItem.getUriConnectBy() + " " +  nItem.getQualifier(targetNode));
				}
				sparql.append(tab + snode.getSparqlID() + " " + predVarName + " " + targetNode.getSparqlID() + " .\n");
				
				// constraint on the props variable
				sparql.append(tab + predVarName + " rdfs:subPropertyOf* " +  this.applyPrefixing(nItem.getUriConnectBy()) + " .\n");
			}
			
	
		} else 
		**/
		
		if (queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT || queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT_WHERE) {
			if (nItem.getQualifier(targetNode).length() > 0) {
				throw new Exception("Qualifier unsupported for CONSTRUCT queries: " + nItem.getKeyName() + nItem.getQualifier(targetNode) );
			}
		}
		
		if (subPropNames == null || subPropNames.size() == 0) {
			// no subProperties: use the connecting URI
			
			// CONSTRUCT uses the binding, where others use the sparqlID 
			String nodeId =  (queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT) ? snode.getBindingOrSparqlID() : snode.getSparqlID();
			String targetId =  (queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT) ? targetNode.getBindingOrSparqlID() : targetNode.getSparqlID();
						
			predStr = this.applyPrefixing(nItem.getUriConnectBy());
			predStr = this.applyQualifier(predStr, nItem.getQualifier(targetNode));
			sparql.append(tab + nodeId + " " + predStr + " " + targetId + " .\n");
			
		} else { // sub-properties
			String predVarName = SparqlToXUtils.safeSparqlVar(snode.getSparqlID() + "_" + nItem.getUriConnectBy()+ " " + targetNode.getSparqlID());
			
			if (queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT) {
				// variable w/o qualifier
				sparql.append(tab + snode.getBindingOrSparqlID() + " " + predVarName + " " + targetNode.getBindingOrSparqlID() + " .\n");
			

			} else if (	queryType == AutoGeneratedQueryTypes.QUERY_DELETE_WHERE ||
					queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT_WHERE) {
				// variable with qualifier
				
				// Apparently SPARQL will not accept a predicate variable name with a qualifier, e.g. ?pred+ or ?(?pred)+
				// It isn't clear that this makes much sense anyway.
				// So this line is removed:
				//      predStr = this.applyQualifier(varName, nItem.getQualifier(targetNode));
				// And varName is used plain as long as there's no qualifier.
				// Exception if there is a qualifier
				if (!nItem.getQualifier(targetNode).isEmpty()) {
					throw new Exception("SemTK can't generate CONSTRUCT or DELETE WHERE clause for property with subproperties and a qualifier: " + nItem.getUriConnectBy() + " " +  nItem.getQualifier(targetNode));
				}
				sparql.append(tab + snode.getSparqlID() + " " + predVarName + " " + targetNode.getSparqlID() + " .\n");
				
				// constraint on the props variable
				sparql.append(tab + this.genSubPropertiesContraint(predVarName, nItem.getUriConnectBy(), subPropNames) + " .\n");
				
			} else {
				// (this|that) with qualifier
				predStr = this.genSubPropertyListParenthesized(nItem.getUriConnectBy(), subPropNames); 
				predStr = this.applyQualifier(predStr, nItem.getQualifier(targetNode));
				sparql.append(tab + snode.getSparqlID() + " " + predStr + " " + targetNode.getSparqlID() + " .\n");
			}
		}
		
		tab = SparqlToXUtils.tabIndent(tab);
		
		// RECURSION
		if (incomingFlag) {
			sparql.append(this.generateSparqlSubgraphClausesNode(queryType, snode, nItem, targetNode, targetObj, doneNodes, doneUnions, tab));
		} else {
			sparql.append(this.generateSparqlSubgraphClausesNode(queryType, targetNode, nItem, targetNode, targetObj, doneNodes, doneUnions, tab));
		}
		tab = SparqlToXUtils.tabOutdent(tab);
		
		// close optional block
		if (blockFlag) {
			tab = SparqlToXUtils.tabOutdent(tab);
			sparql.append(tab + "}\n");
		}
		return sparql.toString();
	}
	
	/**
	 * Generate a values constraint for a property variable, constraining it to a prop or its subprops 
	 *   and only include subprops with the domain of the given nodeUri class.
	 * @param varName
	 * @param nodeUri
	 * @param propUri
	 * @return
	 * @throws Exception
	 */
	private String genSubPropertiesContraint(String varName, String propUri, HashSet<String> subPropNames) throws Exception {
		ArrayList<String> propNames = new ArrayList<String>();
		propNames.add(propUri);
		propNames.addAll(subPropNames); 
		return  ValueConstraint.buildValuesConstraint(varName, propNames, XSDSupportedType.NODE_URI, this.conn.getModelInterface(0));
	}
	
	/**
	 * Generate a parenthesized list (prop1|prop2) of a property and it's subprops
	 *    and only include subprops with the domain of the given nodeUri class.
	 * @param nodeUri
	 * @param propUri
	 * @return
	 * @throws Exception
	 */
	private String genSubPropertyListParenthesized(String propUri, HashSet<String> subPropNames) throws Exception {
		StringBuilder propStr = new StringBuilder();
		propStr.append("(" + this.applyPrefixing(propUri));
		
		for (String subProp : subPropNames) {
			propStr.append("|" + this.applyPrefixing(subProp));
		}
		propStr.append(")");
		return propStr.toString();
	}
	
	/** PropertyItem portion of a subgraph clause.
	 * (no recursion here)
	 * @param queryType
	 * @param snode
	 * @param targetObj
	 * @param prop
	 * @param tab
	 * @return
	 * @throws Exception
	 */
	private String generateSparqlSubgraphClausesPropItem(AutoGeneratedQueryTypes queryType, Node snode, PropertyItem prop, Returnable targetObj, String tab) throws Exception {
		boolean indentFlag = false;
		StringBuilder sparql = new StringBuilder();		
				
		if (prop.getSparqlID().isEmpty()) {
			throw new Error ("Can't create SPARQL for property with empty sparql ID: " + prop.getKeyName());
		}
		// check for being optional...
		if(prop.getOptMinus() == PropertyItem.OPT_MINUS_OPTIONAL && queryType != AutoGeneratedQueryTypes.QUERY_CONSTRUCT){
			sparql.append(tab + "optional{\n");
			tab = SparqlToXUtils.tabIndent(tab);
			indentFlag = true;
		} else if(prop.getOptMinus() == PropertyItem.OPT_MINUS_MINUS && queryType != AutoGeneratedQueryTypes.QUERY_CONSTRUCT){
			sparql.append(tab + "minus {\n");
			tab = SparqlToXUtils.tabIndent(tab);
			indentFlag = true;
		}
		
		// get subPropNames or null for IDK
		HashSet<String> subPropNames = (this.oInfo == null) ? null : this.oInfo.inferSubPropertyNames(prop.getUriRelationship(), snode.getUri());
		
		/**
		// Virtuoso can't handle subPropertyOf:
		// It is a messy presumption for sparql.
		// For now, if there's no oInfo, there are no sub-properties.

		if (subPropNames == null) {
			// don't know if there are sub-properties
			String predVarName = SparqlToXUtils.safeSparqlVar(snode.getSparqlID() + "_" + prop.getUriRelationship());

			if (queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT) {
				// varname no values clause
				sparql.append(tab + snode.getBindingOrSparqlID() + " " + predVarName + " " + prop.getSparqlID() + " .\n");

			} else if (	queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT_WHERE) {
				// varname plus values clause
				sparql.append(tab + snode.getBindingOrSparqlID() + " " + predVarName + " " + prop.getSparqlID() + " .\n");
				sparql.append(tab + predVarName + " rdfs:subPropertyOf* " +  this.applyPrefixing(prop.getUriRelationship()) + " .\n");
				
			} else {   // WHERE and DISTINCT
				// varname plus values clause
				sparql.append(tab + snode.getSparqlID() + " " + predVarName + " " + prop.getSparqlID() + " .\n");
				sparql.append(tab + predVarName + " rdfs:subPropertyOf* " +  this.applyPrefixing(prop.getUriRelationship()) + " .\n");
			} 
			
		} else 
		**/
		if (subPropNames == null || subPropNames.size() == 0) {
			// no sub-properties
			
			// CONSTRUCT uses the binding, where others use the sparqlID 
			String nodeId =  (queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT) ? snode.getBindingOrSparqlID() : snode.getSparqlID();
			
			sparql.append(tab + nodeId + " " + this.applyPrefixing(prop.getUriRelationship()) + " " + prop.getSparqlID() +  " .\n");
		
		} else {
			// prop with sub-props
			String predVarName = SparqlToXUtils.safeSparqlVar(snode.getSparqlID() + "_" + prop.getUriRelationship());

			if (queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT) {
				// varname no values clause
				sparql.append(tab + snode.getBindingOrSparqlID() + " " + predVarName + " " + prop.getSparqlID() + " .\n");

			} else if (	queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT_WHERE) {
				// varname plus values clause
				sparql.append(tab + snode.getBindingOrSparqlID() + " " + predVarName + " " + prop.getSparqlID() + " .\n");
				sparql.append(tab + this.genSubPropertiesContraint(predVarName, prop.getUriRelationship(), subPropNames) + " .\n");
				
			} else if (	queryType == AutoGeneratedQueryTypes.QUERY_DELETE_WHERE) {
				// varname plus values clause
				sparql.append(tab + snode.getSparqlID() + " " + predVarName + " " + prop.getSparqlID() + " .\n");
				sparql.append(tab + this.genSubPropertiesContraint(predVarName, prop.getUriRelationship(), subPropNames) + " .\n");
				
			} else {
				// "normal": parenthesized predicate
				sparql.append(tab + snode.getSparqlID() + " " + this.genSubPropertyListParenthesized(prop.getUriRelationship(), subPropNames)+  " " + prop.getSparqlID() +  " .\n");
			}
			
		}
		
		if (prop.getBinding() != null && queryType != AutoGeneratedQueryTypes.QUERY_CONSTRUCT) {
			sparql.append(tab + "BIND(" + prop.getSparqlID() + " as " +  prop.getBinding() + ") .\n" );
		}
	
		// add in attribute range constraint if there is one 
		if(prop.getConstraints() != null && prop.getConstraints() != ""){
			// add unless this is a CONSTRAINT query on targetObj
			if((queryType != AutoGeneratedQueryTypes.QUERY_CONSTRUCT) && (queryType != AutoGeneratedQueryTypes.QUERY_CONSTRAINT || targetObj == null || prop.getSparqlID() != targetObj.getSparqlID())){
				if(prop.getConstraints() != null && !prop.getConstraints().equalsIgnoreCase("")){
					tab = SparqlToXUtils.tabIndent(tab);
					sparql.append(tab + prop.getConstraints() + " .\n");
					tab = SparqlToXUtils.tabOutdent(tab);
				}
			}
		}
		
		// close optional block.
		if(indentFlag){
			tab = SparqlToXUtils.tabOutdent(tab);
			sparql.append(tab + "} \n");
		}
		return sparql.toString();
	}
	
	private String generateSparqlTypeClause(Node node, String tab, AutoGeneratedQueryTypes queryType) throws Exception  {
		String retval = "";
		// Generates SPARQL to constrain the type of this node if
		// There is no edge that constrains it's type OR
		// the edge(s) that constrain it don't actually include it (they're all
		// super classes, so not enough constraint)
		// or CONSTRUCT
		
		ArrayList<String> constrainedTypes = this.getConnectedRange(node);
		
		if(constrainedTypes.size() == 0 || !constrainedTypes.contains(node.getFullUriName() ) || 
				queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT ||
				queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT_WHERE){
			
			if (this.oInfo == null) {
				// we can't tell about sub-classes
				
				if(queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT) {	
					// ?Binding_or_sparqlid a ?target_type
					retval += tab + node.getBindingOrSparqlID() + " a " + node.getTypeSparqlID() + " .\n";
				
				} else {

					// ?target a ?target_type
					retval += tab + node.getSparqlID() + " a " + node.getTypeSparqlID() + " .\n";
									
					// ?target_type  rdfs:subClassOf*  uir://some#typeName
					retval += tab + node.getTypeSparqlID() +  "  rdfs:subClassOf* " + this.applyPrefixing(node.getFullUriName()) + " .\n";
				}
				
			} else if (this.oInfo.hasSubclass(node.getFullUriName())) {
				// we know there are subClasses, and we oInfo to look them up
				
				if(queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT) {	
					// ?Binding_or_sparqlid a ?target_type
					retval += tab + node.getBindingOrSparqlID() + " a " + node.getTypeSparqlID() + " .\n";
				
				} else {
					// ?target a ?target_type
					retval += tab + node.getSparqlID() + " a " + node.getTypeSparqlID() + " .\n";

					// get a prefixed list of classNames
					ArrayList<String> classNames = new ArrayList<String>();
					classNames.addAll(this.oInfo.getSubclassNames(node.getFullUriName()));
					for (int i=0; i < classNames.size(); i++) {
						classNames.set(i, this.applyPrefixing(classNames.get(i)));
					}
					
					retval += tab + ValueConstraint.buildBestSubclassConstraint(
							node.getTypeSparqlID(), 
							this.applyPrefixing(node.getFullUriName()),
							classNames, 
							this.conn.getInsertInterface())+ " .\n";
				}
			} else {
				if(queryType == AutoGeneratedQueryTypes.QUERY_CONSTRUCT) {	
					// ?Binding_or_sparqlid a ?target_type
					retval += tab + node.getBindingOrSparqlID() + " a " + this.applyPrefixing(node.getFullUriName()) + " .\n";
				
				} else {
					// we know there are no subClasses
					retval += tab + node.getSparqlID() + " a " + this.applyPrefixing(node.getFullUriName()) + " .\n";
				}
			}
		}
		
		return retval;
	}

	public void expandOptionalSubgraphs() throws Exception  {
		// Find nodes with only optional returns
		// and add incoming optional nodeItem so that entire snode is optional
		// then move the optional nodeItem outward until some non-optional return is found
		// this way the "whole chain" becomes optional.
		// Leave original optionals in place
		
		// For nodes with only one non-optional connection, and optional properties
		// make the node connection optional too
		for (Node snode : this.nodes) {
			
			
			// count optional and non-optional returns properties
			int optRet = 0;
			int nonOptRet = (snode.getIsReturned() || snode.getIsBindingReturned()) ? 1 : 0;
			for (PropertyItem prop :  snode.getReturnedPropertyItems()) {
				if (prop.getIsOptional()) {
					optRet += 1;
				} else {
					nonOptRet += 1;
				}
			}
			
			// if all returned props are optional
			if (optRet > 0 && nonOptRet == 0) {		
				ArrayList<Node> connectedSnodes = this.getAllConnectedNodes(snode);
				
				// if there's only one snode connected
				if (connectedSnodes.size() == 1) {
					Node otherSnode = connectedSnodes.get(0);
					ArrayList<NodeItem> nodeItems = this.getNodeItemsBetween(snode, otherSnode);
					
					// if it's only connected once between snode and otherSnode 
					// and connection is non-optional
					// then make it optional
					if (nodeItems.size() == 1) {
						NodeItem nodeItem = nodeItems.get(0);						
						
						// make the nodeItem optional inward
						if (snode.ownsNodeItem(nodeItem) && nodeItem.getOptionalMinus(otherSnode) == NodeItem.OPTIONAL_FALSE) {
							nodeItem.setOptionalMinus(otherSnode, NodeItem.OPTIONAL_REVERSE);
						} 
						if (otherSnode.ownsNodeItem(nodeItem) && nodeItem.getOptionalMinus(snode) == NodeItem.OPTIONAL_FALSE) {
							nodeItem.setOptionalMinus(snode, NodeItem.OPTIONAL_TRUE);
						}
					}
				}
			}
		}
		
		// now move optional nodeItems as far away from subgraph leafs as possible
		boolean changedFlag = true;
		while (changedFlag) {
			changedFlag = false;
			
			// loop through all snodes
			for (Node snode : this.nodes) {
				// count non-optional returns and optional properties
				int nonOptReturnCount = (snode.getIsReturned() || snode.getIsBindingReturned()) ? 1 : 0;
				int optPropCount = 0;
				for (PropertyItem pItem : snode.getReturnedPropertyItems()) {
					if (! pItem.getIsOptional()) {
						nonOptReturnCount++;
					} else if (pItem.getIsReturned() || pItem.getIsBindingReturned()) {
						optPropCount++;
					}
				}
				
				// sort all connecting node items by their optional status: none, in, out
				ArrayList<NodeItem> normItems = new ArrayList<NodeItem>();
				ArrayList<Node>     normItems1 = new ArrayList<Node>();
				ArrayList<NodeItem> optOutItems = new ArrayList<NodeItem>();
				ArrayList<Node>     optOutItems1 = new ArrayList<Node>();
				ArrayList<NodeItem> optInItems= new ArrayList<NodeItem>();
				ArrayList<Node>     optInItems1 = new ArrayList<Node>();
				int optOutMinusCount = 0;
				int optOutOptionalCount = 0;
				
				// outgoing nodes
				ArrayList<NodeItem> nItems = snode.getNodeItemList();
				for (NodeItem nItem : nItems) {
					if (nItem.getConnected()) {	
						for (Node target : nItem.getNodeList()) {
							int opt = nItem.getOptionalMinus(target);
						
							if (opt == NodeItem.OPTIONAL_FALSE) {
								normItems.add(nItem);
								normItems1.add(target);
								
							} else if (opt == NodeItem.OPTIONAL_TRUE) {
								optOutItems.add(nItem);
								optOutItems1.add(target);
								optOutOptionalCount += 1;
								
							} else if (opt == NodeItem.MINUS_TRUE) {
								optOutItems.add(nItem);
								optOutItems1.add(target);
								optOutMinusCount += 1;
								
							} else { // OPTIONAL_REVERSE
								optInItems.add(nItem);
								optInItems1.add(target);
							}
						}
					}
				}
				
				// incoming nodes
				for (NodeItem nItem : this.getConnectingNodeItems(snode)) {
					
					int opt = nItem.getOptionalMinus(snode);
					
					if (opt == NodeItem.OPTIONAL_FALSE) {
						normItems.add(nItem);
						normItems1.add(snode);
	
					} else if (opt == NodeItem.OPTIONAL_REVERSE || opt == NodeItem.MINUS_REVERSE) {
						optOutItems.add(nItem);
						optOutItems1.add(snode);
							
					} else {// OPTIONAL_TRUE
						optInItems.add(nItem);
						optInItems1.add(snode);
					}
					
				}
				
				// if nothing is returned AND
				//  one normal connection AND
				//  >=1 optional outward connections AND
				// no optional in connections AND
				if (nonOptReturnCount == 0 && normItems.size() == 1 && optOutItems.size() >= 1 && optInItems.size() == 0) {
				
					// also can't do anything if there's a mix of OPTIONAL and MINUS outgoing
                    if (optOutOptionalCount == 0 || optOutMinusCount == 0) {
						// set the single normal nodeItem to incoming optional
						NodeItem nItem = normItems.get(0);
						Node target = normItems1.get(0);
						
						if (target != snode) {
							nItem.setOptionalMinus(target,  NodeItem.OPTIONAL_REVERSE);
						} else {
							nItem.setOptionalMinus(target, NodeItem.OPTIONAL_TRUE);
						}
	
						// if there is only one outgoing optional, and no optional props here, 
						// then outgoing optional can be set to non-optional for performance
						if (optOutItems.size() == 1 && optPropCount == 0) {
							NodeItem oItem = optOutItems.get(0);
							Node oTarget = optOutItems1.get(0);
							oItem.setOptionalMinus(oTarget, NodeItem.OPTIONAL_FALSE);
						}
						 
						changedFlag = true;
                    }
				}
			}
		}
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public ArrayList<Node> getOrderedNodeList()  {
		ArrayList<Node> ret = new ArrayList<Node>();
		
		ArrayList<Node> headList = this.getHeadNodes();
		
		for (Node n : headList) {
			ret.add(n);
			ret.addAll(this.getSubNodes(n));
		}
		
		ArrayList<Node> ret2 = new ArrayList<Node>();
		Hashtable hash = new Hashtable();
		
		// remove duplicates from ret
		// build backwards so the last duplicate is the one that remains
		// similar to javascript lastIndexOf approach
		for (int i=ret.size()-1; i >=0 ; i--) {
			Node n = ret.get(i);
			if (hash.get(n.getSparqlID()) == null) {
				ret2.add(0, n);
				hash.put(n.getSparqlID(), 1);
			}
		}
		
		return ret2;
	}
	
	
	/**
	 * Get every nodeItem in the nodegroup that is being used to connect something
	 * @return
	 */
	public ArrayList<NodeItem> getAllNodeItems() {
		ArrayList<NodeItem> ret = new ArrayList<NodeItem>();
		
		for (Node node : this.nodes) {
			ret.addAll( node.getConnectedNodeItems() );
		}
		
		return ret;
	}
	
	public void pruneAllUnused () {
		this.pruneAllUnused(false);
	}

	/**
	 * Prune all unUsed subgraphs off the nodegroup
	 * @param instanceOnly - use instance data check in node.isUsed
	 */
	public void pruneAllUnused (boolean instanceOnly) {
		// prune all unused subgraphs
		ArrayList<Node> pruned = new ArrayList<Node>();
		boolean prunedSomething = true;
		
		// continue until every node has been pruned
		while (prunedSomething) {
			// list is different each time, so start over to find first unpruned node
			prunedSomething = false;
			for (Node node : this.nodes) {
				if (!pruned.contains(node)) {
					pruned.add(node);
					this.pruneUnusedSubGraph(node, instanceOnly);
					prunedSomething = true;
					break;   // go back to "while" since this.SNodeList is now changed
				}
			}
		} 
	}
	    
    /**
     * Bindings appear to cause great slowdowns in Fuseki (others unknown)
     * Remove any stray bindings that aren't returned and don't appear in constraints.
     */
    public void removeUnusedBindings() {
        
        String constraintsText = this.getAllConstraintText();
        for (Returnable r : this.getAllReturnables()) {
            // if binding exists but is not returned
            if (r.getBinding() != null && ! r.getIsBindingReturned()) {
                // if binding isn't in any constraints
                if (! Pattern.matches("\\" + r.getBinding() + "[^a-zA-Z0-9_]", constraintsText)) {
                    r.setBinding(null);
                }
            }
        }
    }
    
    private ArrayList<Returnable> getAllReturnables() {
        ArrayList<Returnable> ret = new ArrayList<Returnable>();
        
        for (Node n : this.nodes) {
            ret.add(n);
            ret.addAll(n.getPropertyItems());
        }
        return ret;
    }
    
    /**
     * get non-null text of all value constraints surrounded by " "
     * @return
     */
    private String getAllConstraintText() {
        StringBuilder ret = new StringBuilder();
        for (Node n : this.nodes) {
            ret.append(" " + n.getValueConstraintStr());
            for (PropertyItem p : n.getPropertyItems()) {
                ret.append(" " + p.getValueConstraintStr());
            }
        }
        ret.append(" ");
        return ret.toString();
    }
    
	/**
	 * Prune subgraphs of node that don't have any nodes.isUsed()==true
	 * @param node
	 * @param instanceOnly
	 * @return
	 */
	public boolean pruneUnusedSubGraph (Node node, boolean instanceOnly) {
		
		if (! node.isUsed(instanceOnly)) {
			ArrayList<Node> subNodes = this.getAllConnectedNodes(node);
			ArrayList<ArrayList<Node>> subGraphs = new ArrayList<ArrayList<Node>>();
			ArrayList<Boolean> subGraphIsUsed = new ArrayList<Boolean>();
			int usedSubGraphCount = 0;
			
			ArrayList<Node> stopList = new ArrayList<Node>();
			stopList.add(node);
			
			// build a subGraph for every connection
			for (int i = 0; i < subNodes.size(); i++) {
				subGraphs.add(this.getSubGraph(subNodes.get(i), stopList));
				subGraphIsUsed.add(false);
				
				for (int j=0; j < subGraphs.get(i).size(); j++) {
					Node n = subGraphs.get(i).get(j);
					
					// if this subGraph isUsed, then note it and break
					if (n.isUsed(instanceOnly))  {
						subGraphIsUsed.set(i, true);
						usedSubGraphCount += 1;
						break;
					}
				}
				if (usedSubGraphCount > 1) break;
			}
			
			// if only one subGraph has nodes that are constrained or returned
			if (usedSubGraphCount < 2) {
				
				// delete any subGraph with no returned or constrained nodes
				for (int i=0; i < subGraphs.size(); i++) {
					if (subGraphIsUsed.get(i) == false) {
						for (int j=0; j < subGraphs.get(i).size(); j++) {
							Node n = subGraphs.get(i).get(j);
							this.deleteNode(n, false);
						}
					}
				}
				
				// recursively walk up the 'needed' subtree
				// pruning off any unUsed nodes and subGraphs
				ArrayList<Node> connList = this.getAllConnectedNodes(node);
				this.deleteNode(node, false);
				for (int i=0; i < connList.size(); i++) {
					this.pruneUnusedSubGraph(connList.get(i), instanceOnly);
				}
				
				return true;
			}
		}
		return false;
	}
	
	public void deleteNode (Node nd, boolean recurse) {
		ArrayList<Node> nodesToRemove = new ArrayList<Node>();
		
		// add the requested node
		nodesToRemove.add(nd);
		
		// if appropriate, get the children recursively.
		if (recurse) {
			nodesToRemove.addAll(this.getSubNodes(nd));
		} 
		
		for (int j=0; j < nodesToRemove.size(); j++) {
			this.removeNode(nodesToRemove.get(j));
		}
		
	}
	
	/**
	 * Delete a node and of all the remaining disconnected islands, keep the one containing keepIslandContaining
	 * @param delNode
	 * @param keepIslandContaining
	 */
	public void deleteNode(Node delNode, Node keepIslandContaining) {
		// delete node, potentially leaving multiple disconnected islands
		this.deleteNode(delNode, false);
		
		// get all nodes in island we want to keep
		ArrayList<Node> island = this.getSubGraph(keepIslandContaining, new ArrayList<Node>());
		ArrayList<Node> toDelete = new ArrayList<Node>();
		for (Node n : this.nodes) {
			if (! island.contains(n)) {
				toDelete.add(n);
			}
		}
		
		// now do the delete (avoiding ConcurrentModificationException)
		for (Node n : toDelete) {
			this.deleteNode(n, false);
		}
	}
	
	public void deleteSubGraph(Node n, Node stopNode) {
		ArrayList<Node> stopList = new ArrayList<Node>();
		stopList.add(stopNode);
		this.deleteSubGraph(n, stopList);
	}

	
	/**
	 * Delete a subgraph 
	 * @param n - start deleting here
	 * @param excludeNode - don't delete this one or "cross" it to find more nodes
	 */
	public void deleteSubGraph(Node n, ArrayList<Node> stopList) {
		ArrayList<String> sparqlIds = new ArrayList<String>();
		
		// remove nodes
		for (Node subNode : this.getSubGraph(n, stopList)) {
			sparqlIds.addAll(subNode.getSparqlIDList());
			this.removeNode(subNode);
		}
		
		// remove value constraints referencing the removed items
		for (String id : sparqlIds) {
			this.removeValueConstraintsContaining(id);
		}
	}
	
	/**
	 * Delete value constraints that refer to a particular id
	 * @param id
	 */
	public void removeValueConstraintsContaining(String id) {
		for (Node n : this.nodes) {
			if (n.getValueConstraint() != null) {
				n.getValueConstraint().removeReferencesToVar(id);
			}
			
			for (PropertyItem p : n.getPropertyItems()) {
				if (p.getValueConstraint() != null) {
					p.getValueConstraint().removeReferencesToVar(id);
				}
			}
		}
	}
	
	/**
	 * remove a node
	 * @param node
	 */
	private void removeNode(Node node) {
		
		// remove the current sNode from all links.
		for (Node ngNode : this.nodes) {			
			for (NodeItem item : ngNode.getNodeItemList()) {
				this.rmFromUnions(ngNode, item, node);
				item.removeNode(node);
			}
		}

		this.removeInvalidOrderBy();

		//unionHash
		for (NodeItem n : node.getNodeItemList()) {
			for (Node s : n.getNodeList()) {
				this.rmFromUnions(node, n, s);
			}
		}
		for (PropertyItem p : node.getPropertyItems()) {
			this.rmFromUnions(node, p);
		}
		this.rmFromUnions(node);
		
		// remove the sNode from the nodeGroup
		this.nodes.remove(node);
		this.idToNodeHash.remove(node.getSparqlID());
	}
	
	public void removeLink(NodeItem nItem, Node target) {
		this.rmFromUnions(this.getNodeItemParentSNode(nItem), nItem, target);
		nItem.removeNode(target);
	}

	
	
	/**
	 * Version for FILTER queries.  Make sure targetObj is also not optional.
	 * @param targetObj
	 * @throws Exception
	 */
	public void unOptionalizeConstrained(Returnable targetObj) throws Exception {
		boolean saveIsReturned = targetObj.getIsReturned();
		ValueConstraint saveConstraint = targetObj.getValueConstraint();
		
		targetObj.setValueConstraint(new ValueConstraint("FILTER(?neverExecuted==1))"));
		targetObj.setIsReturned(true);
		
		this.unOptionalizeConstrained();
		
		targetObj.setValueConstraint(saveConstraint);
		targetObj.setIsReturned(saveIsReturned);
	}
	
	/**
	 * Make sure that any subtrees contain no constrained but optional returns.
	 * Walk any optionals as far from the leaf nodes as possible
	 * without affecting any unconstrianed optionals.
	 * @throws Exception
	 */
	public void unOptionalizeConstrained() throws Exception {
		// loop through each nodeItem
		// System.out.println("unOptionalizeConstrained");
		
		// first check that all property items in the nodegroup are NOT optional if they have constraints
		for (Node n : this.nodes) {
			for (PropertyItem pItem : n.getPropertyItems()) {
				if (pItem.getValueConstraint() != null) {
					pItem.setOptMinus(PropertyItem.OPT_MINUS_NONE);
				}
			}
		}
		
		for (NodeItem nItem : this.getAllNodeItems()) {
			Node owningNode = this.getNodeItemParentSNode(nItem);
			for (Node rangeNode : nItem.getNodeList()) {
				HashSet<Node> upstream = this.getUpstreamSubGraph(owningNode, nItem, rangeNode);
				HashSet<Node> downstream = this.getDownstreamSubGraph(owningNode, nItem, rangeNode);
				int upConstrainedReturns = 0;
				int downConstrainedReturns = 0;
			
				for (Node n : upstream) {
					upConstrainedReturns += n.countConstrainedReturns();
				}
				for (Node n : downstream) {
					downConstrainedReturns += n.countConstrainedReturns();
				}
				
				// if subgraph has Constrained returns, make sure it is not optional
				if (upConstrainedReturns != 0 &&  nItem.getOptionalMinus(rangeNode) == NodeItem.OPTIONAL_REVERSE) {
					LocalLogger.logToStdOut("unoptionalize " + owningNode.getSparqlID() + "->" + nItem.getKeyName() + "->" + rangeNode.getSparqlID());
					nItem.setOptionalMinus(rangeNode, NodeItem.OPTIONAL_FALSE);
				}
				// repeat in downstream direction
				if (downConstrainedReturns != 0 && nItem.getOptionalMinus(rangeNode) == NodeItem.OPTIONAL_TRUE) {
					LocalLogger.logToStdOut("unoptionalize " + owningNode.getSparqlID() + "->" + nItem.getKeyName() + "->" + rangeNode.getSparqlID());
					nItem.setOptionalMinus(rangeNode, NodeItem.OPTIONAL_FALSE);
				}
			}
		}
	}
	
	/**
	 * For an island subgraph, find 0 or 1 nodes with a top-level union key.
	 * That means that the whole island subgraph is a branch of a union.
	 * 
	 * @param startNode
	 * @return unionKey or null
	 */
	private Integer getSubGraphUnionKey(Node startNode) {
		
		for (Node node : this.getSubGraph(startNode, new ArrayList<Node>())) {
			Integer unionKey = this.getUnionKey(node);
			if (unionKey != null) {
				ArrayList<Integer> membership = this.getUnionMembershipList(node);
				if (membership.size() == 1) {
					return membership.get(0);
				}
			}
		}
		return null;
	}
	
	/**
	 * Recursively find a subgraph given a node and a list of nodes who can't be in the subgraph
	 * @param startNode
	 * @param stopList
	 * @return
	 */
	private ArrayList<Node> getSubGraph(Node startNode, ArrayList<Node> stopList) {
		ArrayList<Node> ret = new ArrayList<Node>();
		
		ret.add(startNode);
		ArrayList<Node> conn = this.getAllConnectedNodes(startNode);
		
		for (Node n : conn) {
			if (! stopList.contains(n) && ! ret.contains(n)) {
				ret.addAll(this.getSubGraph(n, ret));
			}
		}
		return ret;
	}
	
	/**
	 * Recursively find the subgraph downstream of a node item specified by the params
	 * @param owningNode
	 * @param nItem
	 * @param rangeNode
	 * @return
	 */
	private HashSet<Node> getDownstreamSubGraph(Node owningNode, NodeItem nItem, Node rangeNode) throws Exception {
		return this.getDownstreamSubGraph(owningNode, nItem, rangeNode, owningNode);
	}
	private HashSet<Node> getDownstreamSubGraph(Node owningNode, NodeItem nItem, Node rangeNode, Node circularNode) throws Exception {
		// System.err.println("Downstream subgraph: " + owningNode.getSparqlID() + " " + nItem.getKeyName() + " " + rangeNode.getSparqlID());
		HashSet<Node> ret = new HashSet<Node>();
		ret.add(rangeNode);
		
		// downstream
		for (NodeItem downstreamItem : rangeNode.getNodeItemList()) {
			for (Node downstreamNode: downstreamItem.getNodeList()) {
				if (downstreamNode == circularNode) {
					throw new Exception("Can't perform this operation on nodegroups with circular connections.");
				}
				ret.addAll(this.getDownstreamSubGraph(rangeNode, downstreamItem, downstreamNode, circularNode));
			}
		}
		
		// upstream
		for (NodeItem upstreamItem : this.getConnectingNodeItems(rangeNode)) {
			Node upstreamNode = this.getNodeItemParentSNode(upstreamItem);
			if (upstreamNode != owningNode || upstreamItem != nItem) {
				if (upstreamNode == circularNode) {
					throw new Exception("Can't perform this operation on nodegroups with circular connections.");
				}
				ret.addAll(this.getUpstreamSubGraph(upstreamNode, upstreamItem, rangeNode, circularNode));
			}
		}
		
		return ret;
	}
	
	/**
	 * Recursively find the subgraph upstream of a node item specified by the params
	 * @param owningNode
	 * @param nItem
	 * @param rangeNode
	 * @return
	 */
	private HashSet<Node> getUpstreamSubGraph(Node owningNode, NodeItem nItem, Node rangeNode) throws Exception {
		return this.getUpstreamSubGraph(owningNode, nItem, rangeNode, rangeNode);
	}
	private HashSet<Node> getUpstreamSubGraph(Node owningNode, NodeItem nItem, Node rangeNode, Node circularNode) throws Exception {
		// System.err.println("Upstream subgraph: " + owningNode.getSparqlID() + " " + nItem.getKeyName() + " " + rangeNode.getSparqlID());
		HashSet<Node> ret = new HashSet<Node>();
		ret.add(rangeNode);
		
		// downstream
		for (NodeItem downstreamItem : owningNode.getNodeItemList()) {
			for (Node downstreamNode: downstreamItem.getNodeList()) {
				if (downstreamItem != nItem || downstreamNode != rangeNode ) {
					if (downstreamNode == circularNode) {
						throw new Exception("Can't perform this operation on nodegroups with circular connections.");
					}
					ret.addAll(this.getDownstreamSubGraph(owningNode, downstreamItem, downstreamNode, circularNode));
				}
			}
		}
		
		// upstream
		for (NodeItem upstreamItem : this.getConnectingNodeItems(owningNode)) {
			Node upstreamNode = this.getNodeItemParentSNode(upstreamItem);
			if (upstreamNode == circularNode) {
				throw new Exception("Can't perform this operation on nodegroups with circular connections.");
			}
			ret.addAll(this.getUpstreamSubGraph(upstreamNode, upstreamItem, owningNode, circularNode));
		}
		
		return ret;
	}
	
	public Node addPath(OntologyPath path, Node anchorNode, OntologyInfo oInfo ) throws Exception  {
		return this.addPath(path, anchorNode, oInfo, false, false);
	}
	
	public Node addPath(OntologyPath path, Node anchorNode, OntologyInfo oInfo, Boolean reverseFlag) throws Exception  {
		return this.addPath(path, anchorNode, oInfo, reverseFlag, false);
	}

	public Node addPath(OntologyPath path, Node anchorNode, OntologyInfo oInfo, Boolean reverseFlag, Boolean optionalFlag) throws Exception  {
		// Adds a path to the canvas.
		// path start class is the new one
		// path end class already exists
		// return the node corresponding to the path's startClass. (i.e. the one
		// the user is adding.)
		
		// reverseFlag:  in diabolic case where path is one triple that starts and ends on same class
		//               if reverseFlag, then connect
		
		// add the first class in the path
		Node retNode = this.addNode(path.getStartClassName(), oInfo);
		Node lastNode = retNode;
		Node node0;
		Node node1;
		int pathLen = path.getLength();
		// loop through path but not the last one
		for (int i = 0; i < pathLen - 1; i++) {
			String class0Uri = path.getClass0Name(i);
			String attUri = path.getAttributeName(i);
			String class1Uri = path.getClass1Name(i);

			// if this hop in path is  lastAdded--hasX-->class1
			if (class0Uri.equals(lastNode.getUri())) {
				node1 = this.returnBelmontSemanticNode(class1Uri, oInfo);
				this.addOneNode(node1, lastNode, null, attUri);
				lastNode = node1;
				
				if (optionalFlag) {
					throw new Exception("Internal error in belmont.js:AddPath(): SparqlGraph is not smart enough\nto add an optional path with links pointing away from the new node.\nAdding path without optional flag.");
					//optionalFlag = false;
				}
			// else this hop in path is class0--hasX-->lastAdded
			} else {
				node0 = this.returnBelmontSemanticNode(class0Uri, oInfo);
				this.addOneNode(node0, lastNode, attUri, null);
				lastNode = node0;
			}
		}

		// link the last two nodes, which by now already exist
		String class0Uri = path.getClass0Name(pathLen - 1);
		String class1Uri = path.getClass1Name(pathLen - 1);
		String attUri = path.getAttributeName(pathLen - 1);

		// link diabolical case from anchor node to last node in path
		if (class0Uri.equals(class1Uri) && reverseFlag ) {
			int opt = optionalFlag ? NodeItem.OPTIONAL_REVERSE : NodeItem.OPTIONAL_FALSE;
			anchorNode.setConnection(lastNode, attUri, opt);
			
		// normal link from last node to anchor node
		} else if (anchorNode.getUri().equals(class1Uri)) {
			int opt = optionalFlag ? NodeItem.OPTIONAL_REVERSE : NodeItem.OPTIONAL_FALSE;
			lastNode.setConnection(anchorNode, attUri, opt);
			
		// normal link from anchor node to last node
		} else {
			int opt = optionalFlag ? NodeItem.OPTIONAL_TRUE : NodeItem.OPTIONAL_FALSE;
			anchorNode.setConnection(lastNode, attUri, opt);
		}
		return retNode;

	}
	
	public Node returnBelmontSemanticNode(String classUri, OntologyInfo oInfo) throws Exception {
		// return a belmont semantic node represented by the class passed from
		// oInfo.
		// PAUL NOTE: this used to be in graphGlue.js
		// But there is no value in keeping oInfo and belmont separate, and
		// combining is elegant.
		this.oInfo = oInfo;
		OntologyClass oClass = oInfo.getClass(classUri);
		if (oClass == null) {
			throw new Exception("Can't find class '" + classUri + "' in the ontology");
		}
		ArrayList<PropertyItem> belprops = new ArrayList<PropertyItem>();
		ArrayList<NodeItem> belnodes = new ArrayList<NodeItem>();

		// set the value for the node name:
		String nome = oClass.getNameString(true);
		String fullNome = oClass.getNameString(false);

		ArrayList<OntologyProperty> props = oInfo.getInheritedProperties(oClass);

		// get a list of the properties not repesenting other nodes.
		for (int i = 0; i < props.size(); i++) {
			String propNameLocal = props.get(i).getName().getLocalName();
			String propNameFull = props.get(i).getName().getFullName();
			String propRangeNameLocal = props.get(i).getRange().getLocalName();
			String propRangeNameFull = props.get(i).getRange().getFullName();

			// is the range a class ?
			if (oInfo.containsClass(propRangeNameFull)) {
				NodeItem p = new NodeItem(propNameFull, propRangeNameLocal, propRangeNameFull);
				belnodes.add(p);

			}
			// range is string, int, etc.
			else {

				// create a new belmont property object and add it to the list.
				PropertyItem p = new PropertyItem(XSDSupportedType.getMatchingValue(propRangeNameLocal), propRangeNameFull, propNameFull);
				belprops.add(p);
			}
		}

		return new Node(nome, belprops, belnodes, fullNome, this);
	}

	/**
	 * Adds one node without making any connections
	 * @param classUri
	 * @param oInfo
	 * @return Node
	 * @throws Exception 
	 * @ 
	 */
	public Node addNode(String classUri, OntologyInfo oInfo) throws Exception  {
		Node node = this.returnBelmontSemanticNode(classUri, oInfo);
		this.addOneNode(node, null, null, null);
		return node;
	}
	
	/**
	 * Add a node constrained to a particular instance
	 * @param classUri
	 * @param oInfo
	 * @param instanceURI - can be null or empty
	 * @return
	 * @throws Exception
	 */
	public Node addNodeInstance(String classUri, OntologyInfo oInfo, String instanceURI) throws Exception {
		Node node = this.addNode(classUri, oInfo);
		if (instanceURI != null && ! instanceURI.isEmpty()) {
			SparqlEndpointInterface sei = (this.conn != null) ? this.conn.getInsertInterface() : null;
			node.addValueConstraint(ValueConstraint.buildFilterInConstraint(node, instanceURI, sei));
		}
		return node;
	}
	
	public Node addNode(String classUri, Node existingNode, String linkFromUri, String linkToUri) throws Exception {
		Node node = this.returnBelmontSemanticNode(classUri, this.oInfo);
		this.addOneNode(node, existingNode, linkFromUri, linkToUri);
		return node;
	}
	
	public void setSparqlConnection(SparqlConnection sparqlConn) {
		this.conn = sparqlConn;
	}
	
	public SparqlConnection getSparqlConnection() {
		return this.conn;
	}
	
	public OntologyInfo getOInfo() {
		return this.oInfo;
	}
	
	public int getNodeCount() {
		return this.nodes.size();
	}
	
	private ArrayList<String> getArrayOfURINames() {
		ArrayList<String> retval = new ArrayList<String>();
		int t = this.nodes.size();
		for (int l = 0; l < t; l++) {
			// output the name
			retval.add(this.nodes.get(l).getUri());
			// alert(this.SNodeList[l].getURI());
		}
		return retval;

	}
	
	/**
	 * Get sparql ids of all nodes in nodegroup
	 * @return
	 */
	public ArrayList<String>  getNodeSparqlIds() {
		ArrayList<String> retval = new ArrayList<String>();

		for (Node n : this.nodes) {
			retval.add(n.getSparqlID());
		}
		return retval;
	}
	
	public String setIsReturned(Returnable r, boolean val) throws Exception {
		if (r instanceof PropertyItem) {
			return this.setIsReturned((PropertyItem) r, val);
		} else {
			return this.setIsReturned((Node) r, val);
		}
	}
	/**
	 * Set a property item to be returned, giving it a SparqlID if needed
	 * @param pItem
	 * @param val
	 * @return the sparqlId
	 * @throws Exception 
	 */
	public String setIsReturned(PropertyItem pItem, boolean val) throws Exception {
		String ret = null;
		if (val && pItem.getSparqlID().isEmpty()) {
			ret = this.changeSparqlID(pItem, pItem.getKeyName());
		} 
		pItem.setIsReturned(val);
		
		return  ret;
	}
	
	/**
	 * Set a node to be returned, giving it a SparqlID if needed
	 * @param pItem
	 * @param val
	 * @return the sparqlId
	 */
	public String setIsReturned(Node node, boolean val) {
		String ret = null;
		if (val && node.getSparqlID().isEmpty()) {
			ret = this.changeSparqlID(node, node.getUri(true));
		}
		node.setIsReturned(val);
		
		return  ret;
	}
	
	public void setIsReturnedAllProps(Node node, boolean isRet) throws Exception {
		for (PropertyItem pItem : node.getPropertyItems()) {
			this.setIsReturned(pItem, isRet);
		}
	}
	
	public void setIsReturnedAllProps(Node node, boolean isRet, int optMinus) throws Exception {
		for (PropertyItem pItem : node.getPropertyItems()) {
			this.setIsReturned(pItem, isRet);
			pItem.setOptMinus(optMinus);
		}
	}
	


	/**
	 * Change an object's sparqlID to something close to requestID
	 * @param obj
	 * @param requestID
	 * @return the actual new requestID
	 */
	public String changeSparqlID(Returnable obj, String requestID) {

		// bail if this is a no-op
		String oldID = obj.getSparqlID();
		if (requestID.equals(oldID)) {
			return requestID;
		}
		
		String newID;
		if (obj instanceof Node) {
			newID = BelmontUtil.generateSparqlID(requestID, this.getAllVariableNames((Node) obj));
			obj.setSparqlID(newID);
			
			this.idToNodeHash.remove(oldID);
			this.idToNodeHash.put(newID, (Node) obj);
			
		} else {
			Node snode = this.getPropertyItemParentSNode((PropertyItem) obj);
			
			newID = BelmontUtil.generateSparqlID(requestID, this.getAllVariableNames(snode, (PropertyItem) obj));
			obj.setSparqlID(newID);
		}
		
		
		this.removeInvalidOrderBy();

		return newID;
	}
	
	/**
	 * Adds a class if there is a path, otherwise returns null
	 * @param classURI
	 * @param oInfo
	 * @return Node
	 * @throws Exception
	 */
	public Node addClassFirstPath(String classURI, OntologyInfo oInfo) throws Exception  {
		return this.addClassFirstPath(classURI, oInfo, null, false);
	}
	public Node addClassFirstPath(String classURI, OntologyInfo oInfo, String domain, Boolean optionalFlag) throws Exception  {
		// attach a classURI using the first path found.
		// Error if less than one path is found.
		// return the new node
		// return null if there are no paths

		// get first path from classURI to this nodeGroup
		this.oInfo = oInfo;
		ArrayList<OntologyPath> paths = oInfo.findAllPaths(classURI, this.getArrayOfURINames(), domain);
		if (paths.size() == 0) {
			return null;
		}
		OntologyPath path = paths.get(0);
		
		// get first node matching anchor of first path
		ArrayList<Node> nlist = this.getNodesByURI(path.getAnchorClassName());
		
		// add sNode
		Node sNode = this.addPath(path, nlist.get(0), oInfo, false, optionalFlag);

		return sNode;
	}
    /**
     * Set a property item to be returned, giving it a SparqlID if needed
     * @param pItem
     * @param val
     * @return the sparqlId
     * @throws Exception 
     */
    public String setValueConstraint(PropertyItem pItem, ValueConstraint vc) throws Exception {
        String ret = null;
        if (vc != null && pItem.getSparqlID().isEmpty()) {
            ret = this.changeSparqlID(pItem, pItem.getKeyName());
        } 
        pItem.setValueConstraint(vc);
        
        return  ret;
    }
    
    public String setValueConstraint(PropertyItem pItem, String vcStr) throws Exception {
        String ret = null;
        ValueConstraint vc = new ValueConstraint(vcStr);
        if (vc != null && pItem.getSparqlID().isEmpty()) {
            ret = this.changeSparqlID(pItem, pItem.getKeyName());
        } 
        pItem.setValueConstraint(vc);
        
        return  ret;
    }

    /**
     * Make sure sparqlID is not blank.  Get it.
     * @param pItem
     * @return
     * @throws Exception
     */
    public String initSparqlID(PropertyItem pItem) throws Exception {
        String ret = null;
        if (pItem.getSparqlID().isEmpty()) {
            ret = this.changeSparqlID(pItem, pItem.getKeyName());
        }         
        return  ret;
    }
	public Node getOrAddNode(String classURI, OntologyInfo oInfo, String domain) throws Exception  {
		return this.getOrAddNode(classURI, oInfo, domain, false, false);
	}
	
	public Node getOrAddNode(String classURI, OntologyInfo oInfo, boolean superclassFlag) throws Exception  {
		return this.getOrAddNode(classURI, oInfo, "", superclassFlag, false);
	}
	
	public Node getOrAddNode(String classURI, OntologyInfo oInfo, String domain, boolean superclassFlag) throws Exception  {
		return this.getOrAddNode(classURI, oInfo, domain, superclassFlag, false);
	}
	

	public Node getOrAddNode(String classURI, OntologyInfo oInfo, String domain, boolean superclassFlag, boolean optionalFlag ) throws Exception  {
		// return first (randomly selected) node with this URI
		// if none exist then create one and add it using the shortest path (see addClassFirstPath)
		// if superclassFlag, then any subclass of classURI "counts"
		// if optOptionalFlag: ONLY if node is added, change first nodeItem connection in path's isOptional to true
		
		// if gNodeGroup is empty: simple add
		this.oInfo = oInfo;
		Node sNode;
		
		if (this.getNodeCount() == 0) {
			sNode = this.addNode(classURI, oInfo);
			
		} else {
			// if node already exists, return first one
			ArrayList<Node> sNodes = new ArrayList<Node>(); 
			
			// if superclassFlag, then any subclass of classURI "counts"
			if (superclassFlag) {
				sNodes = this.getNodesBySuperclassURI(classURI, oInfo);
			// otherwise find nodes with exact classURI
			} else {
				sNodes = this.getNodesByURI(classURI);
			}
			
			if (sNodes.size() > 0) {
				sNode = sNodes.get(0);
			} else {
				sNode = this.addClassFirstPath(classURI, oInfo, domain, optionalFlag);
			}
		}
		return sNode;
	}
	
	public Node getClosestOrAddNode(Node n, String classURI, OntologyInfo oInfo, boolean superclassFlag) throws Exception  {
		// return node closest to n  with this classURI
		// if none exist then create one and add it using the shortest path (see addClassFirstPath)
		// if superclassFlag, then any subclass of classURI "counts"
		
		// if gNodeGroup is empty: simple add
		this.oInfo = oInfo;
		Node sNode;
		
		if (this.getNodeCount() == 0) {
			sNode = this.addNode(classURI, oInfo);
			
		} else {
			// if node already exists, return first one
			ArrayList<Node> sNodes = new ArrayList<Node>(); 
			
			// if superclassFlag, then any subclass of classURI "counts"
			if (superclassFlag) {
				sNodes = this.getNodesBySuperclassURI(classURI, oInfo);
			// otherwise find nodes with exact classURI
			} else {
				sNodes = this.getNodesByURI(classURI);
			}
			
			if (sNodes.size() > 1) {
				int fewestHops = this.getNodeCount();
				sNode = sNodes.get(0);
				for (Node sn : sNodes) {
					int hops = this.getHopsBetween(n, sn);
					if (hops > -1 && hops < fewestHops) {
						sNode = sn;
					}
				}

			} else if (sNodes.size() == 1) {
				sNode = sNodes.get(0);
				
			} else {
				sNode = this.addClassFirstPath(classURI, oInfo, null, false);
			}
		}
		return sNode;
	}
	
	public Node getNodeItemParentSNode(NodeItem nItem) {
		for (Node n : this.nodes) {
			if (n.getNodeItemList().contains(nItem)) {
				return n;
			}
		}
		return null;
	}
	
	public Node getPropertyItemParentSNode(PropertyItem pItem) {
		for (Node n : this.nodes) {
			if (n.getPropertyItems().contains(pItem)) {
				return n;
			}
		}
		return null;
	}
	
	public int getHopsBetween(Node n1, Node n2) {
		HashSet<Node> visited = new HashSet<Node>();
		return this.getHopsBetween(n1, n2, visited, 0);
	}
	
	private int getHopsBetween(Node n1, Node n2, HashSet<Node> visited, int soFar) {
		if (n1 == n2) {
			return soFar;
			
		} else {
			
			visited.add(n1);
			
			ArrayList<Node> connected = n1.getConnectedNodes();
			for (Node v : visited) {
				connected.remove(v);
			}
			
			if (connected.size() == 0) {
				return -1;
			} else {
				for (Node c : connected) {
					int hops = this.getHopsBetween(c, n2, visited, soFar + 1);
					if (hops > 0) {
						return hops;
					}
				}
			}
		}
		return -1;
	}
	
	/**
	 * Get all nodes one-hop from given node, regardless of direction
	 * @param node
	 * @return
	 */
	private ArrayList<Node> getAllConnectedNodes(Node node) {
		ArrayList<Node> ret = new ArrayList<Node>();
		ret.addAll(node.getConnectedNodes());
		ret.addAll(this.getConnectingNodes(node));
		return ret;
	}
	
	private ArrayList<NodeItem> getNodeItemsBetween(Node sNode1, Node sNode2) {
		// return a list of node items between the two nodes
		// Ahead of the curve: supports multiple links between snodes
		ArrayList<NodeItem> ret = new ArrayList<NodeItem>();
		
		for (NodeItem i : sNode1.getNodeItemList()) {
			if (i.getNodeList().contains(sNode2)) {
				ret.add(i);
			}
		}
		
		for (NodeItem i : sNode2.getNodeItemList()) {

			if (i.getNodeList().contains(sNode1)) {
				ret.add(i);
			}
		}
		
		return ret;
	}
	
	/**
	 * Get all nodes that have nodeItems pointing to node
	 * @param sNode
	 * @return
	 */
	private ArrayList<Node> getConnectingNodes(Node sNode) {
		ArrayList<Node> ret = new ArrayList<Node>();
		for (Node n : this.nodes) {
			if (n.getConnectingNodeItems(sNode).size() > 0 ) {
				ret.add(n);
			}
		}
		return ret;
	}

	/**
	 * Get all nodeItems in the nodegroup that point to sNode
	 * @param sNode
	 * @return
	 */
	public ArrayList<NodeItem> getConnectingNodeItems(Node sNode) {
		// get any nodeItem in the nodeGroup that points to sNode
		ArrayList<NodeItem> ret = new ArrayList<NodeItem>();
		for (Node n : this.nodes) {
			for (NodeItem nItem : n.getConnectingNodeItems(sNode) ) {
				ret.add(nItem);
			}
		}
		return ret;
	}
	
	private ArrayList<Node> getSubNodes(Node topNode) {
		ArrayList<Node> subNodes = new ArrayList<Node>();
		
		ArrayList<Node> connectedNodes = topNode.getConnectedNodes();
		
		subNodes.addAll(connectedNodes);
		
		for (Node n : connectedNodes) {
			ArrayList<Node> innerSubNodes = this.getSubNodes(n);
			subNodes.addAll(innerSubNodes);
		}
		
		return subNodes;
	}
	
	private ArrayList<Node> getHeadNodes()  {
		ArrayList<Node> ret = new ArrayList<Node>();
		
		for (Node n : nodes) {
			int connCount = 0;
			for (Node o : nodes) {
				if (o.checkConnectedTo(n)) {
					++connCount;
					break;
				}
			}
			
			if (connCount == 0) {
				ret.add(n);
			}
		}
		
		if (!nodes.isEmpty() && ret.isEmpty()) {
			ret.add(nodes.get(0));
			// Danger in belmont.js getOrderedNodeList(): No head nodes found.  Graph is totally circular.
		}
		
		return ret;
	}
	
	private Node getNextHeadNode(ArrayList<Node> skipNodes) throws Exception  {
		if (skipNodes.size() == this.nodes.size()) {
			return null;
		}
		
		HashMap<String,Integer> optHash = this.calcOptionalHash(skipNodes);
		HashMap<String,Integer> linkHash = this.calcIncomingLinkHash(skipNodes);
		
		String retID = null;
		int minLinks = 99;
		
		// both hashes have same keys: loop through valid snode SparqlID's
		for (String id : optHash.keySet()) {
			// find nodes that are not optional
			if (optHash.get(id) == 0) {
				// choose node with lowest number of incoming links
				if (retID == null || linkHash.get(id) < minLinks) {
					retID = id;
					minLinks = linkHash.get(id);
					// be efficient
					if (minLinks == 0) { break; }
				}
			}
		}
		// throw an error if no nodes have optHash == 0
		if (retID == null) {
			throw new Exception("Internal error in NodeGroup.getHeadNextHeadNode(): No head nodes found. Probable cause: no non-optional semantic nodes.");
		}
		
		return this.getNodeBySparqlID(retID);
	}
	
	private HashMap<String, Integer> calcIncomingLinkHash (ArrayList<Node> skipNodes) throws Exception {
		// so linkHash[snode.getSparqlID()] == count of incoming nodeItem links
		
		HashMap<String, Integer> linkHash = new HashMap<String, Integer>();
		
		// initialize hash
		for (Node snode : this.nodes) {
			if (! skipNodes.contains(snode)) {
				linkHash.put(snode.getSparqlID(), 0);
			}
		}
		
		// loop through all snodes
		for (Node fromSnode : this.nodes) {
			
			if (! skipNodes.contains(fromSnode)) {
				
				// loop through all nodeItems
				for (NodeItem nodeItem : fromSnode.getNodeItemList()) {
					
					for (Node toSnode : nodeItem.getNodeList()) {
						
						if (nodeItem.getOptionalMinus(toSnode) == NodeItem.OPTIONAL_REVERSE || 
								nodeItem.getOptionalMinus(toSnode) == NodeItem.MINUS_REVERSE  ||
								this.isReverseUnion(fromSnode, nodeItem, toSnode)) {
							// OPTIONAL_REVERSE reverses the direction of "incoming"
							Integer val = linkHash.get(fromSnode.getSparqlID());
							linkHash.put(fromSnode.getSparqlID(), val + 1);
						} else {
							// normal case: increment count
							Integer val = linkHash.get(toSnode.getSparqlID());
							// if toSnode is not in skipNodes, then increment
							if (val != null) {
								linkHash.put(toSnode.getSparqlID(), val + 1);
							}
						}
					}
				}
			}
		}
		return linkHash;
	}
	
	private HashMap<String, Integer>  calcOptionalHash(ArrayList<Node> skipNodes) throws Exception  {
		
		// ---- set optHash ----
		// so optHash[snode.getSparqlID()] == count of nodeItems indicating this node is optional
		HashMap<String, Integer> optHash = new HashMap<String, Integer>();

		// initialize optHash
		for (Node snode : this.nodes) {

			if (! skipNodes.contains(snode)) {
				optHash.put(snode.getSparqlID(), 0);
			}
		}
		
		// loop through all snodes
		for (Node snode : this.nodes) {
			
			if (! skipNodes.contains(snode)) {
				
				// loop through all nodeItems
				for (NodeItem nodeItem : snode.getNodeItemList()) {
					
					// loop through all connectedSNodes
					for (Node targetSNode : nodeItem.getNodeList()) {
						
						// if found an optional nodeItem
						int opt = nodeItem.getOptionalMinus(targetSNode);
						
						ArrayList<Node> subGraph = new ArrayList<Node>();
						
						// get subGraph(s) on the optional side of the nodeItem
						if (opt == NodeItem.OPTIONAL_TRUE) {
							ArrayList<Node> stopList = new ArrayList<Node>();
							stopList.add(snode);
							subGraph.addAll(this.getSubGraph(targetSNode, stopList));
							
						} else if (opt == NodeItem.OPTIONAL_REVERSE) {
							ArrayList<Node> stopList = new ArrayList<Node>();
							stopList.add(targetSNode);
							subGraph.addAll(this.getSubGraph(snode, stopList));
						}
							
						// increment every node on the optional side of the nodeItem
						for (Node k : subGraph) {
							int val = optHash.get(k.getSparqlID());
							optHash.put(k.getSparqlID(), val + 1);
						}	
					}
				}
			}
		}
		
		return optHash;
	}
	
	private ArrayList<String> getConnectedRange(Node node) throws Exception  {
		ArrayList<String> retval = new ArrayList<String>();
		
		
		ArrayList<NodeItem> nodeItems = this.getConnectingNodeItems(node);
		for (NodeItem ni : nodeItems) {
			if (ni.getOptionalMinus(node) != NodeItem.OPTIONAL_REVERSE && ni.getOptionalMinus(node) != NodeItem.MINUS_REVERSE) {
				String uriValueType = ni.getUriValueType();
				if (!retval.contains(uriValueType)) {
					retval.add(uriValueType);
				}
			}
		}
		
		return retval;
	}
	
	/*
	 * PEC TODO: some of the following oInfo parameters are optional (empty oInfo works fine?)
	 *           and some are not.  It is confusing.  Can they be renamed or commented.
	 */
	public String generateSparqlDelete(OntologyInfo oInfo) throws Exception {
		return this.generateSparqlDelete(null, oInfo);
	}
	
	public String generateSparqlDelete(String post, OntologyInfo oInfo) throws Exception {
		this.buildPrefixHash();
		
		String primaryBody = this.genDeletionLeader(post, oInfo);
		if(primaryBody == null || primaryBody.isEmpty() || primaryBody == ""){ throw new NoValidSparqlException("nothing given to delete.");}
		
		String whereBody = this.getDeletionWhereBody(post, oInfo);
		
		StringBuilder retval = new StringBuilder();
		SparqlEndpointInterface endpoint = this.conn.getDeleteInterface();
		retval.append(this.generateSparqlPrefix() + "\n");
		retval.append("DELETE { GRAPH <" + endpoint.getGraph() + "> {" + primaryBody + "}}") ;

		String usingClause = SparqlToXUtils.generateSparqlFromOrUsing("", "USING", this.conn, this.oInfo);		
		
		if(whereBody.length() != 0){	// there might be no where clause... 
			retval.append("\n" + usingClause + "WHERE {\n" + whereBody + "}\n");
		}
		
		retval.append(this.generateLimitClause(-1));

		return retval.toString();
	}

	private String genDeletionLeader(String post, OntologyInfo oInfo) throws Exception {
		
		StringBuilder retval = new StringBuilder();
		
		for(Node n : this.nodes){
			// get the node's deletion info.... this includes the properties, nodeitems and (potentially) the node itself.
			
			if( n.getDeletionMode() != NodeDeletionTypes.NO_DELETE){
				// we have something to do...
				retval.append(generateNodeDeletionSparql(n, false));
			}
			// check the properties.
			for( PropertyItem pi : n.getPropertyItems() ){
				if(pi.getIsMarkedForDeletion()){
					HashSet<String> subPropNames = (this.oInfo == null) ? new HashSet<String>() : this.oInfo.inferSubPropertyNames(pi.getUriRelationship(), n.getUri());
					if (subPropNames.size() == 0) {
						// no sub-props: use prefixed property uri
						retval.append("   " + n.sparqlID + " " +  this.applyPrefixing( pi.getUriRelationship() ) + " " +  pi.sparqlID + " . \n");
					} else {
						// sub-props: use variable
						String varName = SparqlToXUtils.safeSparqlVar(n.getSparqlID() + "_" + pi.getUriRelationship());
						retval.append("   " + n.sparqlID + " " + varName + " " + pi.sparqlID + " .\n");
					}
				}
			}
			// check the nodeItems. 
			for( NodeItem ni : n.getNodeItemList() ) {
				ArrayList<Node> nic = ni.getSnodesWithDeletionFlagsEnabledOnThisNodeItem();
				// get subPropNames or null for IDK
				HashSet<String> subPropNames = (this.oInfo == null) ? null : this.oInfo.inferSubPropertyNames(ni.getUriConnectBy(), n.getUri());
				for( Node connected : nic ){
					if (subPropNames != null && subPropNames.size() == 0) {
						// no subproperties: use prefixed propURI
						retval.append("   " + n.sparqlID + " " + this.applyPrefixing( ni.getUriConnectBy() ) + " " + connected.sparqlID +  " . \n");
					} else {
						// subproperties: use varname
						String varName = SparqlToXUtils.safeSparqlVar(n.getSparqlID() + "_" + ni.getUriConnectBy() + "_" + connected.sparqlID);
						retval.append("   " + n.sparqlID + " " + varName + " " + connected.sparqlID +  " . \n");
					}
				}
			}	
			// this should contain all the deletion info for the node itself.
		}
		// ship it out.
		return retval.toString();
	}
	
	private String generateNodeDeletionSparql(Node n, Boolean inWhereClause) throws Exception {
		String retval = "";
		String indent = "   ";
		NodeDeletionTypes delMode = n.getDeletionMode();
		
		if(delMode == NodeDeletionTypes.TYPE_INFO_ONLY){
			retval += indent + n.sparqlID + " rdf:type  " + n.sparqlID + "_type_info . \n";
		}
		else if(delMode == NodeDeletionTypes.FULL_DELETE){
			retval += indent + n.sparqlID + " rdf:type  " + n.sparqlID + "_type_info . \n";
			
			// outgoing links
			if(inWhereClause){ retval += " optional {"; } 
			retval += indent + n.sparqlID + " " + n.sparqlID + "_related_predicate_outgoing " + n.sparqlID + "_related_object_target . \n";
			if(inWhereClause){ retval += " } "; } 
			
			// incoming links
			if(inWhereClause){ retval += " optional {"; } 
			retval += indent + n.sparqlID + "_related_subject " + n.sparqlID + "_related_predicate_incoming " + n.sparqlID + " . \n";
			if(inWhereClause){ retval += " } "; } 
		}
		else if(delMode == NodeDeletionTypes.LIMITED_TO_NODEGROUP){
			retval += indent + n.sparqlID + " rdf:type  " + n.sparqlID + "_type_info . \n";

			// get all incoming references to this particular node (in the current NodeGroup scope)
			// and schedule them for removal...
			for(Node ndIncomingCandidate : this.nodes){
				// get the node items and check the targets.
				for(NodeItem ni : ndIncomingCandidate.getConnectingNodeItems(n)){
					// set it so that the consequences of the decision are seen in the post-decision ND
					ni.setSnodeDeletionMarker(n, true);
					// generate the sparql snippet related to this deletion.
					retval += indent + ndIncomingCandidate.sparqlID + " " + this.applyPrefixing(ni.getUriConnectBy()) + " " + n.sparqlID + " . \n";
				}
			}
			
			// generation of property deletion clauses is handled outside this method.
		}
		else if(delMode == NodeDeletionTypes.LIMITED_TO_MODEL){
			throw new Exception("NodeDeletionTypes.LIMITED_TO_MODEL is not currently implemented.");
		}
		else{
			// fail politely when the user/caller has no 
			throw new Exception("generateNodeDeletionSparql :: node with sparqlID (" + n.getSparqlID() + ") has an unimplemented DeletionMode (" + delMode.name() + ").");	
		}
		return retval;
	}

	public String getDeletionWhereBody(String post, OntologyInfo oInfo) throws Exception {
		StringBuilder retval = new StringBuilder();
		
		ArrayList<Node> doneNodes = new ArrayList<Node>();
		ArrayList<Integer> doneUnions = new ArrayList<Integer>();
		Node headNode = this.getNextHeadNode(doneNodes);
		while (headNode != null) {
			// for each node, get the subgraph clauses, including constraints.
			retval.append(this.generateSparqlSubgraphClausesNode(	AutoGeneratedQueryTypes.QUERY_DELETE_WHERE, headNode, null, null, null, doneNodes, doneUnions, "   "));
			headNode = this.getNextHeadNode(doneNodes);
		}
		
		return retval.toString();
	}
	
	/**
	 * Return a list of nodes ordered by headnodes depth first
	 * @return
	 * @throws Exception
	 */
	private ArrayList<Node> getOrderedNodes() throws Exception {
		ArrayList<Node> ret = new ArrayList<Node>();
		Node headNode = this.getNextHeadNode(ret);
		while (headNode != null) {
			this.addOrderedSubnodes(headNode, ret);
			headNode = this.getNextHeadNode(ret);
		}
		return ret;
	}
	
	/**
	 * Buddy to getOrderedNodes
	 * @param snode
	 * @param ret
	 */
	private void addOrderedSubnodes(Node snode, ArrayList<Node> ret) {
		if(ret.contains(snode)){
			return;
		}
		else{
			ret.add(snode);
			for(NodeItem nItem : snode.getNodeItemList()) {
				
				for (Node n : nItem.getNodeList()) {
					addOrderedSubnodes(n, ret);
				}
			}
		}
	}
	
	/**
	 * Assign boring deterministic sparqlIds
	 * @throws Exception
	 */
	public void assignStandardSparqlIds() throws Exception {
		int i=0;
		for (Node n : this.getOrderedNodes()) {
			this.changeSparqlID(n, n.getUri(true) + String.valueOf(i));
			n.clearBinding();
			for (PropertyItem p : n.getPropertyItems()) {
				if (! p.getSparqlID().isEmpty()) {
					this.changeSparqlID(p, p.getKeyName() + String.valueOf(i));
					p.clearBinding();
				}
			}
		}
	}
	
	public String generateSparqlInsert(OntologyInfo oInfo, SparqlEndpointInterface endpoint) throws Exception {
		return this.generateSparqlInsert(null, oInfo, endpoint);
	}
	
	public String generateSparqlInsert(String sparqlIDSuffix, OntologyInfo oInfo, SparqlEndpointInterface endpoint) throws Exception {
		this.buildPrefixHash();
		
		String retval = "";
		// get the primary insert body.
		String primaryBody = this.getInsertLeader(sparqlIDSuffix, oInfo);
		
		// get the where clause body
		String whereBody = this.getInsertWhereBody(sparqlIDSuffix, oInfo);
		String usingClause = SparqlToXUtils.generateSparqlFromOrUsing("  ", "USING", this.conn, this.oInfo);
		retval =  this.generateSparqlPrefix() + " INSERT { GRAPH <" + endpoint.getGraph() + "> {\n" + primaryBody + "} }\n " + usingClause + " WHERE {" + whereBody + "}\n";
		
		return retval;
		
	}
	
	/**
	 * Generate insert for a group of nodegroups, which must be identical except for their instance data
	 * @param ngList - WARNING: will add to prefixHash as side-effect  (We'll miss you, Justin)
	 * @param oInfo
	 * @return sparql insert statement
	 * @throws Exception
	 */
	public static String generateCombinedSparqlInsert(ArrayList<NodeGroup> ngList, OntologyInfo oInfo, SparqlEndpointInterface endpoint) throws Exception {
		
		HashMap<String, String> prefixHash = new HashMap<String, String>();
		String totalInsertHead = "";
		String totalInsertWhere = "";
		NodeGroup ng = null;
		
		for (int i=0; i < ngList.size(); i++) {
			ng = ngList.get(i);
			
			// pass prefixHash on to next nodegroup
			if(i > 0){
				ng.rebuildPrefixHash(prefixHash);	// add new elements, as needed.
			}
			prefixHash = ng.getPrefixHash();
			
			String seq = "__" + i;
			totalInsertHead  += ng.getInsertLeader(seq, oInfo);
			totalInsertWhere += ng.getInsertWhereBody(seq, oInfo);
				
		}
		
		if (totalInsertHead.length() == 0) {
			throw new NothingToInsertException("No data to insert");
		}
		// NOTE: the last NodeGroup should have all the prefixes of all the needed groups.
		//       this way, we only need to get it's prefixes. 
		String query =  ng.generateSparqlPrefix() + " INSERT { GRAPH <" + endpoint.getGraph() + "> {\n" + totalInsertHead + "} }\n WHERE {" + totalInsertWhere + " } ";

		return query;
	}

	public String getInsertLeader(String sparqlIDSuffix, OntologyInfo oInfo) throws Exception  {
		// this method creates the top section of the insert statements.
		// the single argument is used to post-fix the sparqlIDs, if required. 
		// this is used in the generation of bulk insertions. 
		this.buildPrefixHash();
		
		String retval = "";
		if(sparqlIDSuffix == null){ sparqlIDSuffix = "";}
		
		if (this.nodes.size() < 1) {
			throw new Exception("Can't generate INSERT query on nodegroup with zero nodes");
		}
		
		// loop through the nodes and get any values we may need. 
		for(Node node : this.nodes){
			
			Boolean nodeIsEnum = oInfo.classIsEnumeration(node.getFullUriName());
			Boolean instanceIsBlank = false;
			String instanceValue = node.getInstanceValue();
			
			instanceIsBlank = (instanceValue == null ||  instanceValue.isEmpty());

			String subject = null;
			if (!instanceIsBlank) {
				
				String nodeVal = this.applyBaseURI(instanceValue);
				nodeVal = this.applyPrefixing(nodeVal);
				nodeVal = this.applyAngleBrackets(nodeVal);  // VARISH's unusual URI's with no '#'
				subject = nodeVal;

			} else {
				String sparqlID = node.getSparqlID() + sparqlIDSuffix;
				subject = sparqlID;
			}
			
			
			// makes sure to indicate that this node was of its own type. it comes up.
		
			/**
			 * There is a subtlety here where one can get into trouble if a node has no instance value (the instance uri)
			 * this comes in two flavors:
			 * 1. the node referenced is an enumeration and we should drop it. can't go inventing new ones just because
			 * 2. basically make a blank node because we need those one on the path. this one is interesting because we need to makes sure
			 *    there is an in or out edge to our unnamed node, or else we are just making clutter. 
			 *    
			 *    Note: for now, we are going to make the clutter. 
			 */
			
			// only add this node if the current instance should be included. 
			if((!nodeIsEnum) || (nodeIsEnum && !instanceIsBlank)){   
				if(!nodeIsEnum && !node.isInstanceLookedUp()){	    // do not include type info when the target is an enum or URI was looked up
					retval += "\t" + subject + " a " + this.applyPrefixing(node.getFullUriName()) + " . \n";
				}
				// insert each property we know of. 
				for(PropertyItem prop : node.getPropertyItems()){
					for(String inst : prop.getInstanceValues()){
						retval += "\t" + subject + " " + this.applyPrefixing(prop.getUriRelationship()) + " " + prop.getValueType().buildRDF11ValueString(inst, "XMLSchema") + " .\n";  
					}
				}
				
				// insert a line for each node item
				for(NodeItem ni : node.getNodeItemList()){
					for(Node target : ni.getNodeList()){
						
						String targetInstanceValue = target.getInstanceValue();
						
						String predicate = null;
						if (targetInstanceValue != null && !targetInstanceValue.isEmpty()) {
							
							String nodeVal = this.applyBaseURI(targetInstanceValue);
							nodeVal = this.applyPrefixing(nodeVal);
							nodeVal = this.applyAngleBrackets(nodeVal);  // VARISH's unusual URI's with no '#'
							predicate = nodeVal;

						} else {
							String sparqlID = node.getSparqlID() + sparqlIDSuffix;
							predicate = sparqlID;
						}
						
						
						retval += "\t" + subject + " " + this.applyPrefixing(ni.getUriConnectBy()) + " " + predicate + " .\n";
					}
				}
			}
		}
		
		return retval;
	}
	
	public String getInsertWhereBody(String sparqlIDSuffix, OntologyInfo oInfo) throws Exception  {
		
		this.buildPrefixHash();
		StringBuilder sparql = new StringBuilder();
		
		if (sparqlIDSuffix == null) {
			sparqlIDSuffix = "";
		}
		
		for (Node node : this.nodes) {
			String sparqlId = node.getSparqlID() + sparqlIDSuffix;
			

			Boolean nodeIsEnum = oInfo.classIsEnumeration(node.getFullUriName());
			Boolean instanceIsBlank = false;
			String instanceValue = node.getInstanceValue();
			
			instanceIsBlank = (instanceValue == null ||  instanceValue.isEmpty());

			
			if (!instanceIsBlank) {
				// URI was specified
				
				// -- Moved to generateInsertLeader()--
				
				// String nodeVal = this.applyBaseURI(node.getInstanceValue());
				// nodeVal = this.applyPrefixing(nodeVal);
				// nodeVal = this.applyAngleBrackets(nodeVal);  // VARISH's unusual URI's with no '#'
				// sparql.append("\tBIND (" + nodeVal + " AS " + sparqlId + ").\n");
			
			} else if(instanceIsBlank && !nodeIsEnum){
				ArrayList<PropertyItem> constrainedProps = node.getConstrainedPropertyObjects();
				
				if (!constrainedProps.isEmpty()) {
					// node is constrained
					
					for (PropertyItem pi : constrainedProps) {
				
						sparql.append(
								" " + sparqlId + " " + this.applyPrefixing(pi.getUriRelationship()) +
								" " + pi.getSparqlID() + " . " + pi.getConstraints() + " .\n");
					}
				}
				
				else {
					// node not constrained, create new URI
					
					// create new instance
					// we have to be able to check if the Node has "instanceValue" set. if it does. we want to reuse that. if not, kill it.
					if (node.getInstanceValue() != null && !node.getInstanceValue().equals("") && !node.getInstanceValue().isEmpty()) {
						String nodeVal = this.applyBaseURI(node.getInstanceValue());
						
						sparql.append("\tBIND (iri(\"" + this.applyPrefixing(nodeVal) + "\") AS " + sparqlId + ").\n");
					}
					else {
						
						sparql.append("\tBIND (iri(concat(\"" + this.applyPrefixing(UriResolver.DEFAULT_URI_PREFIX) + "\", \"" + UUID.randomUUID().toString() + "\")) AS " + sparqlId + ").\n");
						
						
					}
				}
			}
			
		}
		
		return sparql.toString();
	}

	public JSONObject toJson()  {
		return this.toJson(null);
	}

	/**
	 * 
	 * @param dontDeflatePropItems - null=don't deflate ;  non-null=deflate
	 * @return
	 * @
	 */
	@SuppressWarnings("unchecked")
	public JSONObject toJson(ArrayList<PropertyItem> dontDeflatePropItems)  {
		JSONObject ret = new JSONObject();
		
		// get list in order such that linked nodes always preceed the node that
		// links to them
		ArrayList<Node> orig = this.getOrderedNodeList();
		ArrayList<Node> snList = new ArrayList<Node>();
		for (int i = orig.size()-1; i >=0; i--) {
			snList.add(orig.get(i));
		}
		
		ret.put("version", VERSION);
		ret.put("limit", this.limit);
		ret.put("offset", this.offset);
		
		// orderBy
		JSONArray orderBy = new JSONArray();
		for (int i=0; i < this.orderBy.size(); i++) {
			orderBy.add(this.orderBy.get(i).toJson());
		}
		ret.put("orderBy", orderBy);
		
		// sNodeList
		JSONArray sNodeList = new JSONArray();
		for (int i=0; i < snList.size(); i++) {
			sNodeList.add(snList.get(i).toJson(dontDeflatePropItems));
		}
		ret.put(JSON_KEY_NODELIST, sNodeList);
		
		// unionHash
		JSONObject unionHash = new JSONObject();
        for (Integer k : this.unionHash.keySet()) {
        	JSONArray val = new JSONArray();
        	for (String s : this.unionHash.get(k)) {
        		val.add(s);
        	}
            unionHash.put(k.toString(), val);
        }
        ret.put(JSON_KEY_UNIONHASH, unionHash);
		return ret;
	}
	
	/**
	 * For every runtime constrainable object that isRuntimeConstrained 
	 * (flag is set, regardless of whether there is a constraint), 
	 * build RuntimeConstrainedObject
	 * @return
	 */
	public HashMap<String, RuntimeConstraintMetaData> getRuntimeConstrainedItems(){
		HashMap<String, RuntimeConstraintMetaData> retval = new HashMap<String, RuntimeConstraintMetaData>();
		
		// go through all of the nodegroup contents and send back the collection.
		for(Node curr : this.nodes){
			if(curr.getIsRuntimeConstrained()){ 
				// this one is constrained. add it to the list. 
				
				RuntimeConstraintMetaData currConst = new RuntimeConstraintMetaData((Returnable)curr, RuntimeConstraintManager.SupportedTypes.NODE);
				retval.put(curr.sparqlID, currConst);
			}
			else{
				// do nothing.
			}
			
			// check the properties to make sure that we get them all. 
			for(PropertyItem pi : curr.getPropertyItems()){
				if(pi.getIsRuntimeConstrained()){
					RuntimeConstraintMetaData currConst = new RuntimeConstraintMetaData((Returnable)pi, RuntimeConstraintManager.SupportedTypes.PROPERTYITEM);
					retval.put(pi.sparqlID, currConst);
				}
				else{
					// do nothing.
				}
			}
		}
		return retval;
	}
	
	/**
	 * Inflates (adds all unused properties) and ...
	 * @param oInfo
	 * @throws Exception - if validation fails
	 */
	public void inflateAndValidate(OntologyInfo oInfo) throws Exception  {
		this.inflateAndValidate(oInfo, null, null, null, null);
	}
	
	/**
	 * This version inflates and validates fully even after errors are found
	 * @param oInfo
	 * @param modelErrMsgs - populated with error messages (if null, first model error throws exception)
	 * @param invalidItems - populated with NodeGroupItemStr for invalid items, if not null
	 * @throws Exception - non-model errors, or first model error if modelErrMsgs is null
	 */
	public void inflateAndValidate(OntologyInfo oInfo, ImportSpec importSpec, ArrayList<String> modelErrMsgs, ArrayList<NodeGroupItemStr> invalidItems, ArrayList<String> warnings) throws Exception  {
		this.oInfo = oInfo;
		if (oInfo.getNumberOfClasses() == 0 && this.getNodeList().size() > 0) {
			throw new ValidationException("Model contains no classes. Nodegroup can't be validated.");
		}
		
		for (Node n : this.getNodeList()) {
			n.inflateAndValidate(oInfo, importSpec, modelErrMsgs, invalidItems, warnings);
		}
	}
	
	public void validateAgainstModel(OntologyInfo oInfo) throws Exception  {
		this.oInfo = oInfo;
		if (oInfo.getNumberOfClasses() == 0 && this.getNodeList().size() > 0) {
			throw new ValidationException("Model contains no classes. Nodegroup can't be validated.");
		}
		
		for (Node n : this.getNodeList()) {
			n.validateAgainstModel(oInfo);
		}
	}
	
	/**
	 * Associate an oInfo for sparql generation without any validation or inflation
	 * @param oInfo
	 * @throws Exception
	 */
	public void noInflateNorValidate(OntologyInfo oInfo) throws Exception  {
		this.oInfo = oInfo;
	}
	
	/**
	 * Add a constraint such that this node is a unique instance 
	 * from other nodes with the same URI
	 * @param n
	 * @throws Exception
	 */
	public void addUniqueInstanceConstraint(Node n) throws Exception {
		ArrayList<Node> nodeList = this.getNodesByURI(n.getFullUriName());
		for (Node other : nodeList) {
			if (other != n) {
				String vc = ValueConstraint.buildFilterConstraintWithVariable(n, "!=", other.getSparqlID());
				n.addValueConstraint(vc);
			}
		}
	}
	
	/**
	 * Change a node URI
	 * Note this is the simplest.
	 * @param node
	 * @param newURI
	 */
	public void changeItemDomain(Node node, String newURI) {
		node.setFullURIname(newURI);
	}

	/**
	 * Change invalid property to a valid one, MERGING with what's already there.
	 * Note: in a normal inflateAndValidate the correct one will be empty
	 * @param batteryNode
	 * @param prop
	 * @param newURI
	 * @throws Exception
	 */
	public PropertyItem changeItemDomain(Node node, PropertyItem prop, String newURI) throws Exception {
		
        PropertyItem mergeIntoProp = node.getPropertyByURIRelation(newURI);
        if (mergeIntoProp == null) throw new Exception ("Can't find property to merge into: " + newURI);

        String oldItemStr = (new NodeGroupItemStr(node, prop)).getStr();
        String newItemStr = (new NodeGroupItemStr(node, mergeIntoProp)).getStr();
        
        mergeIntoProp.merge(prop);   // throws exception if prop is not empty
        node.rmPropItem(prop);
       
        this.updateUnionHashPropItems(oldItemStr, newItemStr);

        return mergeIntoProp;
		
	}
	
	/**
	 * 
	 * @param node
	 * @param nItem
	 * @param target - can be null ( move entire nodeItem ) or non-null (just move target)
	 * @param newURI
	 * @return
	 * @throws Exception
	 */
	public NodeItem changeItemDomain(Node node, NodeItem nItem, Node target, String newURI) throws Exception {
		
        NodeItem mergeIntoNItem = node.getNodeItem(newURI);
        if (mergeIntoNItem == null) throw new Exception ("Can't find property to merge into: " + newURI);

        String oldItemStr = (new NodeGroupItemStr(node, nItem, null)).getStr();
        String newItemStr = (new NodeGroupItemStr(node, mergeIntoNItem, null)).getStr();
        
        mergeIntoNItem.merge(nItem, target); 
        
        if (! nItem.getConnected()) {
        	node.rmNodeItem(nItem);
        }
       
        this.updateUnionHashNodeItems(oldItemStr, newItemStr);

        return mergeIntoNItem;
		
	}

	public void deleteProperty(Node node, PropertyItem prop) {
		node.rmPropItem(prop);
		String itemStr = (new NodeGroupItemStr(node, prop)).getStr();
		this.updateUnionHashPropItems(itemStr, null);
	}
	
	public void deleteNodeItem(Node node, NodeItem nItem) {
		node.rmNodeItem(nItem);
		String itemStr = (new NodeGroupItemStr(node, nItem, null)).getStr();
		this.updateUnionHashNodeItems(itemStr, null);
	}
	
	/**
	 * Change range of a property item
	 * @param pItem
	 * @param newURI
	 * @throws Exception
	 */
	public void changeItemRange(PropertyItem pItem, String newURI) throws Exception {
		// very simple
		pItem.setRange(newURI);
	}
	
	public void changeItemRange(NodeItem nItem, String newURI) throws Exception {
		// very simple
		nItem.setRange(newURI);
	}
	
	/**
	 * Update the union hash when a property has changed URI domain
	 * Not necessary to call this for Nodes
	 * @param oldItemStr
	 * @param newItemStr - or null to delete
	 */
	private void updateUnionHashPropItems(String oldItemStr, String newItemStr) {
		for (Integer key : this.unionHash.keySet()) {
			ArrayList<String> val = this.unionHash.get(key);
			for (int i=0; i < val.size(); i++) {
				if (val.get(i).equals(oldItemStr)) {
					if (newItemStr != null) {
						val.set(i, newItemStr);
						break;
					} else {
						val.remove(i);
						break;
					}
				}
			}
		}
	}
	
	/**
	 * Update the union hash when a nodeItem has changed URI domain
	 * @param oldItemStr
	 * @param newItemStr - or null to delete
	 */
	private void updateUnionHashNodeItems(String oldItemStr, String newItemStr) {
		String oldPrefix = NodeGroupItemStr.getNodeItemPrefix(oldItemStr);
		String newPrefix = NodeGroupItemStr.getNodeItemPrefix(newItemStr);
		for (Integer key : this.unionHash.keySet()) {
			ArrayList<String> val = this.unionHash.get(key);
			for (int i=0; i < val.size(); i++) {
				if (val.get(i).startsWith(oldPrefix)) {
					if (newItemStr != null) {
						val.set(i, NodeGroupItemStr.replaceNodeItemPrefix(val.get(i), newPrefix));
						break;
					} else {
						val.remove(i);
						break;
					}
				}
			}
		}
	}

}
