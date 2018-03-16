/**
 ** Copyright 2016-8 General Electric Company
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


package com.ge.research.semtk.load.utility;

import java.net.URI;
import java.sql.Time;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import com.ge.research.semtk.belmont.AutoGeneratedQueryTypes;
import com.ge.research.semtk.belmont.BelmontUtil;
import com.ge.research.semtk.belmont.Node;
import com.ge.research.semtk.belmont.NodeGroup;
import com.ge.research.semtk.belmont.PropertyItem;
import com.ge.research.semtk.belmont.ValueConstraint;
import com.ge.research.semtk.belmont.ValuesConstraint;
import com.ge.research.semtk.load.transform.Transform;
import com.ge.research.semtk.load.transform.TransformInfo;
import com.ge.research.semtk.load.utility.UriResolver;
import com.ge.research.semtk.ontologyTools.OntologyInfo;
import com.ge.research.semtk.resultSet.Table;
import com.ge.research.semtk.resultSet.TableResultSet;
import com.ge.research.semtk.sparqlX.SparqlEndpointInterface;
import com.ge.research.semtk.sparqlX.SparqlResultTypes;
import com.ge.research.semtk.sparqlX.SparqlToXUtils;
import com.ge.research.semtk.utility.Utility;

/*
 * NOT a port of javascript ImportSpec.
 * Java-specific and highly optimized (ha) for ingestion 
 * Handles the importSpec portion of SparqlGraphJson
 * 
 * WARNING: This is shared by THREADS.  It must remain THREAD SAFE.
 */
public class ImportSpecHandler {
	static final String LOOKUP_MODE_NO_CREATE = "noCreate";
	static final String LOOKUP_MODE_CREATE = "createIfMissing";
	
	JSONObject importspec = null; 
	
	JSONObject nodegroupJson = null;      
	HashMap<Integer, JSONObject> lookupNodegroupsJson = new HashMap<Integer, JSONObject>();       // cache of pruned nodegroups ready for lookup
	HashMap<Integer, String>    lookupMode = new HashMap<Integer, String>();
	HashMap<String, Integer>   colNameToIndexHash = new HashMap<String, Integer>();
	HashMap<String, Transform> transformHash = new HashMap<String, Transform>();
	HashMap<String, String>    textHash = new HashMap<String, String>();
	HashMap<String, String>    colNameHash = new HashMap<String, String>();
	HashMap<String, Integer>   colsUsed = new HashMap<String, Integer>();    // count of cols used.  Only includes counts > 0
	
	ImportMapping importMappings[] = null;
	HashMap<Integer, ArrayList<ImportMapping>> lookupMappings = new HashMap<Integer, ArrayList<ImportMapping>>();  // for each node index, the mappings that do URI lookup

	String [] colsUsedKeys = null;
	
	UriResolver uriResolver;
	OntologyInfo oInfo;
	
	UriCache uriCache = new UriCache();
	
	SparqlEndpointInterface nonThreadSafeEndpoint = null;  // Endpoint for looking up URI's.  It is not thread safe, so it must be copied before being used.
	
	public ImportSpecHandler(JSONObject importSpecJson, JSONObject ngJson, OntologyInfo oInfo) throws Exception {
		this.importspec = importSpecJson; 
		
		// reset the nodegroup and store as json (for efficient duplication)
		NodeGroup ng = NodeGroup.getInstanceFromJson(ngJson);
		ng.reset();
		this.nodegroupJson = ng.toJson();
		
		this.oInfo = oInfo;
		
		this.colHashesFromJson(   (JSONArray) importSpecJson.get(SparqlGraphJson.JKEY_IS_COLUMNS));
		this.transformsFromJson((JSONArray) importSpecJson.get(SparqlGraphJson.JKEY_IS_TRANSFORMS));
		this.textHashFromJson(     (JSONArray) importSpecJson.get(SparqlGraphJson.JKEY_IS_TEXTS));
		this.nodesFromJson((JSONArray) importSpecJson.get(SparqlGraphJson.JKEY_IS_NODES));
		String userUriPrefixValue = (String) this.importspec.get(SparqlGraphJson.JKEY_IS_BASE_URI);
		
		// check the value of the UserURI Prefix
		// LocalLogger.logToStdErr("User uri prefix set to: " +  userUriPrefixValue);
	
		this.uriResolver = new UriResolver(userUriPrefixValue, oInfo);
		
		this.errorCheckImportSpec();
	}
	
	public ImportSpecHandler(JSONObject importSpecJson, ArrayList<String> headers, JSONObject ngJson, OntologyInfo oInfo) throws Exception{
		this(importSpecJson, ngJson, oInfo);
		this.setHeaders(headers);
	}

	public void setEndpoint(SparqlEndpointInterface endpoint) {
		this.nonThreadSafeEndpoint = endpoint;
	}
	
	/**
	 * Set the data source headers
	 * @param headers
	 * @throws Exception
	 */
	public void setHeaders(ArrayList<String> headers) throws Exception {
		HashMap<String, Integer> oldNameToIndexHash = this.colNameToIndexHash;
		HashMap<String, Integer> newNameToIndexHash = new HashMap<String, Integer>();
		HashMap<Integer, Integer> translateHash = new HashMap<Integer, Integer>();
		
		// build hashes
		int counter = 0;
		for(String h : headers){
			String name = h.toLowerCase();
			// build new colNameToIndexHash
			newNameToIndexHash.put(name, counter);
			// build a translation hash
			translateHash.put(oldNameToIndexHash.get(name), counter);
			counter += 1;
		}
		
		HashSet<MappingItem> done = new HashSet<MappingItem>();
		
		// change every mapping item's column index
		for (int i=0; i < this.importMappings.length; i++) {
			for (MappingItem mItem : this.importMappings[i].getItemList()) {
				if (!done.contains(mItem)) {
					mItem.updateColumnIndex(translateHash, oldNameToIndexHash);
					done.add(mItem);
				}
			}
		}
		
		// repeat for URI lookup mappings
		for (Integer i : this.lookupMappings.keySet()) {
			for (ImportMapping map : this.lookupMappings.get(i)) {
				for (MappingItem mItem : map.getItemList()) {
					if (!done.contains(mItem)) {
						mItem.updateColumnIndex(translateHash, oldNameToIndexHash);
						done.add(mItem);
					}
				}
			}
		}
		
		// use the new name-to-index hash
		this.colNameToIndexHash = newNameToIndexHash;
	}
	
	public String getUriPrefix() {
		return uriResolver.getUriPrefix();
	}
	
	/**
	 * Populate the transforms with the correct instances based on the importspec.
	 * @throws Exception 
	 */
	private void transformsFromJson(JSONArray transformsJsonArr) throws Exception{
		
		if(transformsJsonArr == null){ 
			// in the event there was no transform block found in the JSON, just return.
			// thereafter, there are no transforms looked up or found.
			return;}
		
		for (int j = 0; j < transformsJsonArr.size(); ++j) {
			JSONObject xform = (JSONObject) transformsJsonArr.get(j);
			String instanceID = (String) xform.get(SparqlGraphJson.JKEY_IS_TRANS_ID); // get the instanceID for the transform
			String transType = (String) xform.get(SparqlGraphJson.JKEY_IS_TRANS_TYPE); // get the xform type 
			
			// go through all the entries besides "name", "transType", "transId" and 
			// add them to the outgoing HashMap to be sent to the transform creation.
			int totalArgs = TransformInfo.getArgCount(transType);
			
			// get the args.
			HashMap<String, String> args = new HashMap<String, String>();
			for(int argCounter = 1; argCounter <= totalArgs; argCounter += 1){
				// get the current argument
				args.put("arg" + argCounter, (String) xform.get("arg" + argCounter));
			}
			
			// get the transform instance.
			Transform currXform = TransformInfo.buildTransform(transType, instanceID, args);
			
			// add it to the hashMap.
			this.transformHash.put(instanceID, currXform);
		}
	}
	
	/**
	 * Populate the texts with the correct instances based on the importspec.
	 * @throws Exception 
	 */
	private void textHashFromJson(JSONArray textsJsonArr) throws Exception{
		
		if(textsJsonArr == null){ 
			return;
		}
		
		for (int j = 0; j < textsJsonArr.size(); ++j) {
			JSONObject textJson = (JSONObject) textsJsonArr.get(j);
			String instanceID = (String) textJson.get(SparqlGraphJson.JKEY_IS_TEXT_ID); 
			String textVal = (String) textJson.get(SparqlGraphJson.JKEY_IS_TEXT_TEXT);  
			this.textHash.put(instanceID, textVal);
		}
	}

	/**
	 * Populate the texts with the correct instances based on the importspec.
	 * @throws Exception 
	 */
	private void colHashesFromJson(JSONArray columnsJsonArr) throws Exception{
		
		if(columnsJsonArr == null){ 
			return;
		}
		
		for (int j = 0; j < columnsJsonArr.size(); ++j) {
			JSONObject colsJson = (JSONObject) columnsJsonArr.get(j);
			String colId = (String) colsJson.get(SparqlGraphJson.JKEY_IS_COL_COL_ID);      
			String colName = ((String) colsJson.get(SparqlGraphJson.JKEY_IS_COL_COL_NAME)).toLowerCase();  
			this.colNameHash.put(colId, colName);
			this.colNameToIndexHash.put(colName, j);      // initialize colIndexHash with columns in the order provided
		}
	}
	
	/**
	 * 
	 * @param nodesJsonArr
	 * @throws Exception
	 */
	private void nodesFromJson(JSONArray nodesJsonArr) throws Exception {
		NodeGroup tmpImportNg = NodeGroup.getInstanceFromJson(this.nodegroupJson);
		tmpImportNg.clearOrderBy();
		ArrayList<ImportMapping> mappingsList = new ArrayList<ImportMapping>();
		// clear cols used
		colsUsed = new HashMap<String, Integer>();  
		ImportMapping mapping = null;
		
		// loop through .nodes
		for (int i = 0; i < nodesJsonArr.size(); i++){  
			
			// ---- URI ----
			JSONObject nodeJson = (JSONObject) nodesJsonArr.get(i);
			String nodeSparqlID = nodeJson.get(SparqlGraphJson.JKEY_IS_NODE_SPARQL_ID).toString();
			int nodeIndex = tmpImportNg.getNodeIndexBySparqlID(nodeSparqlID);
			if (nodeIndex == -1) {
				throw new Exception("Error in ImportSpec JSON: can't find node in nodegroup: " + nodeSparqlID);
			}
			// lookup mode
			if (nodeJson.containsKey(SparqlGraphJson.JKEY_IS_NODE_LOOKUP_MODE)) {
				String mode = (String)nodeJson.get(SparqlGraphJson.JKEY_IS_NODE_LOOKUP_MODE);
				switch (mode) {
				case ImportSpecHandler.LOOKUP_MODE_CREATE:
				case ImportSpecHandler.LOOKUP_MODE_NO_CREATE:
					this.lookupMode.put(nodeIndex, mode);
					break;
				default:
					throw new Exception("Unknown " + SparqlGraphJson.JKEY_IS_NODE_LOOKUP_MODE + ": " + mode);
				}
			}
			
			// build mapping if it isn't empty AND it isn't a URILookup
			if (nodeJson.containsKey(SparqlGraphJson.JKEY_IS_MAPPING)) {
				JSONArray mappingJsonArr = (JSONArray) nodeJson.get(SparqlGraphJson.JKEY_IS_MAPPING);
				if (mappingJsonArr.size() > 0) {
					
					mapping = new ImportMapping();
					
					// get node index
					String type = (String) nodeJson.get(SparqlGraphJson.JKEY_IS_NODE_TYPE);
					mapping.setIsEnum(this.oInfo.classIsEnumeration(type));
					mapping.setImportNodeIndex(nodeIndex);
					setupMappingItemList(mappingJsonArr, mapping);
					
					// add non-lookup mappings to mapping list
					if (!nodeJson.containsKey(SparqlGraphJson.JKEY_IS_URI_LOOKUP)) {
						mappingsList.add(mapping);
					}
				}
			}
			// handle URILookup, even if mapping was missing or blank
			if (nodeJson.containsKey(SparqlGraphJson.JKEY_IS_URI_LOOKUP)) {
				this.setupUriLookup(nodeJson, mapping, tmpImportNg);
			}
			
			// ---- Properties ----
			if (nodeJson.containsKey(SparqlGraphJson.JKEY_IS_MAPPING_PROPS)) {
				JSONArray propsJsonArr = (JSONArray) nodeJson.get(SparqlGraphJson.JKEY_IS_MAPPING_PROPS);
				Node snode = tmpImportNg.getNode(nodeIndex);
				
				for (int p=0; p < propsJsonArr.size(); p++) {
					JSONObject propJson = (JSONObject) propsJsonArr.get(p);
					
					mapping = null;
					// build mapping if it isn't empty
					if (propJson.containsKey(SparqlGraphJson.JKEY_IS_MAPPING)) {
						JSONArray mappingJsonArr = (JSONArray) propJson.get(SparqlGraphJson.JKEY_IS_MAPPING);	
						if (mappingJsonArr.size() > 0) {
							
							mapping = new ImportMapping();
							
							// populate the mapping
							mapping.setImportNodeIndex(nodeIndex);
							String uriRel = (String)propJson.get(SparqlGraphJson.JKEY_IS_MAPPING_PROPS_URI_REL);
							int propIndex = snode.getPropertyIndexByURIRelation(uriRel);
							if (propIndex == -1) {
								throw new Exception("Error in ImportSpec JSON: can't find property in nodegroup: " + nodeSparqlID + "->" + uriRel);
							}
							mapping.setPropItemIndex(propIndex);
							setupMappingItemList(mappingJsonArr, mapping);
							
							// add non-lookup mappings to mapping list
							if (!propJson.containsKey(SparqlGraphJson.JKEY_IS_URI_LOOKUP)) {
								mappingsList.add(mapping);
							}
						}
					}
					
					// handle URILookup, even if mapping was missing or blank
					if (propJson.containsKey(SparqlGraphJson.JKEY_IS_URI_LOOKUP)) {
						this.setupUriLookup(propJson, mapping, tmpImportNg);
					}
				}
			}
		}
		
		// create some final efficient arrays
		this.importMappings = mappingsList.toArray(new ImportMapping[mappingsList.size()]);
		this.colsUsedKeys = this.colsUsed.keySet().toArray(new String[this.colsUsed.size()]);
		this.setupLookupNodegroups();
	}
	
	private void errorCheckImportSpec() throws Exception {
		// no longer illegal
		// nothing at all to check right now
		
//		for (int i=0; i < this.importMappings.length; i++) {
//			ImportMapping importMap = this.importMappings[i];
//			if (importMap.isNode() && this.lookupMappings.containsKey(importMap.getImportNodeIndex())) {
//				NodeGroup tmpImportNg = NodeGroup.getInstanceFromJson(this.nodegroupJson);
//				Node node = tmpImportNg.getNode(importMap.getImportNodeIndex());
//				throw new Exception("Node is slated for lookup and also has mapping for URI: " + node.getSparqlID());
//			}
//		}
	}
	
	/**
	 * Build lookupNodegroups by adding sample data & pruning.
	 * Then find the right lookupNodeIndex for each lookup mapping
	 * @throws Exception
	 */
	private void setupLookupNodegroups() throws Exception {
		ArrayList<String> sample = new ArrayList<String>();
		sample.add("sample");
		
		NodeGroup importNg = NodeGroup.getInstanceFromJson(this.nodegroupJson);
		
		// for each node with lookupMapping(s)
		for (Integer importNodeIndex : this.lookupMappings.keySet()) {
			NodeGroup lookupNg = NodeGroup.getInstanceFromJson(this.nodegroupJson);
			
			// add a sample value for each
			for (ImportMapping map : this.lookupMappings.get(importNodeIndex)) {
				Node node = lookupNg.getNode(map.getImportNodeIndex());
				
				if (map.isNode()) {
					node.setValueConstraint(new ValueConstraint(ValueConstraint.buildValuesConstraint(node, sample)));
				} else {
					PropertyItem propItem = node.getPropertyItem(map.getPropItemIndex());
					
					// make sure there's a sparql ID
					if (propItem.getSparqlID().equals("")) {
						String sparqlID = BelmontUtil.generateSparqlID(propItem.getKeyName(), importNg.getSparqlNameHash());
						propItem.setSparqlID(sparqlID); 
					}
					
					// apply value constraint
					propItem.setValueConstraint(new ValueConstraint(ValueConstraint.buildValuesConstraint(propItem, sample)));
				}
			}
			
			// prune
			lookupNg.getNode(importNodeIndex).setIsReturned(true);
			lookupNg.pruneAllUnused();
			
			// make another pass through lookupMappings and add the lookupNodeIndex
			// which is the node index after pruning
			for (ImportMapping map : this.lookupMappings.get(importNodeIndex)) {
				// find sparqlID in importNg
				String nodeID = importNg.getNode(map.getImportNodeIndex()).getSparqlID();
				// find same node in the lookupNg
				int lookupIndex = lookupNg.getNodeIndexBySparqlID(nodeID);
				map.setLookupNodeIndex(lookupIndex);
			}
			
			// save the lookupNodegroupJson
			this.lookupNodegroupsJson.put(importNodeIndex, lookupNg.toJson());
		}
	}
	
	/**
	 * Add a lookup ImportMapping to this.lookupMappings in the right place(s).   For any node this is looking up.
	 * @param uriLookupJsonArr
	 * @param mapping - mapping of the node or property which owns this URI lookup, or null if none
	 * @param tmpNodegroup - example nodegroup
	 * @throws Exception - lots of error checks for bad JSON
	 */
	private void setupUriLookup(JSONObject nodeOrPropJson, ImportMapping mapping, NodeGroup tmpNodegroup) throws Exception {
			
		
		// get item name in case of error messages
		String name;
		if (nodeOrPropJson.containsKey(SparqlGraphJson.JKEY_IS_NODE_SPARQL_ID)) {
			name = (String) nodeOrPropJson.get(SparqlGraphJson.JKEY_IS_NODE_SPARQL_ID);
		} else {
			name = (String) nodeOrPropJson.get(SparqlGraphJson.JKEY_IS_MAPPING_PROPS_URI_REL);
		}
		
		// error check missing or empty mapping
		if (mapping == null) {		
			throw new Exception("Error in ImportSpec. Item is a URI lookup but has no mapping: " + name );
		} else if (mapping.getItemList().size() < 1) {
			throw new Exception("Error in ImportSpec. Item is a URI lookup but has empty mapping: " + name );
		}
		
		// 
		JSONArray uriLookupJsonArr = (JSONArray) nodeOrPropJson.get(SparqlGraphJson.JKEY_IS_URI_LOOKUP);
		if (uriLookupJsonArr.size() == 0) {
			throw new Exception("Error in ImportSpec: Empty URI lookup: " + name);
				
		} else {
			
			// loop through the sparql ID's of nodes this item is looking up
			for (int j=0; j < uriLookupJsonArr.size(); j++) {
				String lookupSparqlID = (String)uriLookupJsonArr.get(j);
				int lookupNodeIndex = tmpNodegroup.getNodeIndexBySparqlID(lookupSparqlID);
				if (lookupNodeIndex == -1) {
					throw new Exception ("Error in ImportSpec. Invalid sparqlID in URI lookup: " + lookupSparqlID );
				}
				
				// add mapping to this.lookupMappings
				if (!this.lookupMappings.containsKey(lookupNodeIndex)) {
					this.lookupMappings.put(lookupNodeIndex, new ArrayList<ImportMapping>());
				}
				// add copies of the mapping to each lookupMapping
				this.lookupMappings.get(lookupNodeIndex).add(ImportMapping.importSpecCopy(mapping));
			}
		} 
	}
	/**
	 * Put mapping items from json into an ImportMapping and update colUsed
	 * @param mappingJsonArr
	 * @param mapping
	 * @throws Exception 
	 */
	private void setupMappingItemList(JSONArray mappingJsonArr, ImportMapping mapping) throws Exception {
		
		for (int j=0; j < mappingJsonArr.size(); j++) {
			JSONObject itemJson = (JSONObject) mappingJsonArr.get(j);
			
			// mapping item
			MappingItem mItem = new MappingItem();
			mItem.fromJson(	itemJson, 
							this.colNameHash, this.colNameToIndexHash, this.textHash, this.transformHash);
			mapping.addItem(mItem);
			
			if (itemJson.containsKey(SparqlGraphJson.JKEY_IS_MAPPING_COL_ID)) {
				// column item
				String colId = (String) itemJson.get(SparqlGraphJson.JKEY_IS_MAPPING_COL_ID);
				String colName = this.colNameHash.get(colId);
				// colsUsed
				if (this.colsUsed.containsKey(colId)) {
					this.colsUsed.put(colName, this.colsUsed.get(colName) + 1);
				} else {
					this.colsUsed.put(colName, 1);
				}
			} 
		}
	}
	
	/**
	 * Create a nodegroup to ingest a single record
	 * Always performs URILookup (checking UriCache first), possibly setting to NOT_FOUND
	 * 
	 * In order to accommodate threading and generation of unknown Uris,
	 * this function must always be called twice:
	 * Option 1:  "pre-check"
	 * 		buildImportNodegroup(record, false)  // do validation and URI lookup.  Threaded if desired.
	 *                                           // Caller join threads and make sure all validations passed, or quit.
	 *      generateNotFoundURIs()               // generate any legally not found URI's after validation threads are joined
	 *      buildImportNodegroup(record, true)   // quicker generation using cached URI's and no validation
	 *                                           // This can be threaded
     *                                           // caller should do the insert on the nodegroup
	 *                                           
	 * Option 2: "single pass"
	 * 		buildImportNodegroup(record, true)   // No validation of types.  URI lookup with silent failure. Threaded if desired.    
	 *                                           // Even though not validating, this pass is needed to discover NOT_FOUND Uri's                
	 *      generateNotFoundURIs()               // generate any legally not found URI's after threads are joined
	 *      buildImportNodegroup(record, false)  // validation and nodegroup generation using cached URI's
	 *                                           // This can be threaded
	 *                                           // caller should insert nodegroup.  Incomplete load possible.
	 *                                           // caller report any incomplete loads                                        
	 * @param record
	 * @param skipValidation
	 * @return
	 * @throws Exception
	 */
	public NodeGroup buildImportNodegroup(ArrayList<String> record, boolean skipValidation) throws Exception{

		// create a new nodegroup copy. 
		NodeGroup retNodegroup = NodeGroup.getInstanceFromJson(this.nodegroupJson);
		retNodegroup.clearOrderBy();
		
		if(record  == null){ throw new Exception("incoming record cannot be null for ImportSpecHandler.getValues"); }
		if(this.colNameToIndexHash.isEmpty()){ throw new Exception("the header positions were never set for the importspechandler"); }
		
		// fill in all URI's, possibly with NOT_FOUND if that's legal
		try {
			this.lookupAllUris(retNodegroup, record);
		} catch (Exception e) {
			// swallow URI lookup exception if we're skipping validation
			// PEC TODO:  I'd like to throw virutoso errors
			//            But there is some other errors (what are they?) that we want to swallow.
			if (skipValidation) {
				throw e;
			}
		}
		
		// do mappings
		for (int i=0; i < this.importMappings.length; i++) {
			this.addMappingToNodegroup(retNodegroup, this.importMappings[i], record, skipValidation);
		}
		
		// also do lookupMappings for any URI that was just generated
		for (int i=0; i < retNodegroup.getNodeCount(); i++) {
			if (this.uriCache.isGenerated(retNodegroup.getNode(i).getInstanceValue())) {
				for (ImportMapping lookupMapping : this.lookupMappings.get(i)) {
					this.addMappingToNodegroup(retNodegroup,  lookupMapping, record, skipValidation);
				}
			}
		}
		
		// prune nodes that no longer belong (no uri and no properties)
		retNodegroup.pruneAllUnused(true);
		
		// set URI for nulls
		retNodegroup = this.setURIsForNullNodes(retNodegroup);
		return retNodegroup;
	}
	
	/**
	 * After a possibly threaded pass where lookupURI labeled some URI's as NOT_FOUND
	 * Now go through and generate GUIDs for them.
	 * @throws Exception
	 */
	public void generateNotFoundURIs() throws Exception {
		this.uriCache.generateNotFoundURIs(this.uriResolver);
	}
	
	/**
	 * Use record to add mapping value to an import nodegroup
	 * @param importNodegroup
	 * @param mapping
	 * @param record
	 * @param skipValidation
	 * @throws Exception
	 */
	private void addMappingToNodegroup(NodeGroup importNodegroup, ImportMapping mapping, ArrayList<String> record, boolean skipValidation) throws Exception {
		String builtString = mapping.buildString(record);
		Node node = importNodegroup.getNode(mapping.getImportNodeIndex());
		PropertyItem propItem = null;
		
		if (mapping.isProperty()) {
			// ---- property ----
			if(builtString.length() > 0) {
				propItem = node.getPropertyItem(mapping.getPropItemIndex());
				builtString = validateDataType(builtString, propItem.getValueType(), skipValidation);						
				propItem.addInstanceValue(builtString);
			}
			
		} else {				
			
			// ---- node ----
			
			// Mapping is invalid if URI has already been looked up.  Return.
			if (node.getInstanceValue() != null)
				return;
						
			// if build string is null
			if(builtString.length() < 1){
				node.setInstanceValue(null);
			}
			
			// use built string
			else{
				String uri = this.uriResolver.getInstanceUriWithPrefix(node.getFullUriName(), builtString);
				if (! SparqlToXUtils.isLegalURI(uri)) { throw new Exception("Attempting to insert ill-formed URI: " + uri); }
				node.setInstanceValue(uri);
			}
		}
	}
	
	/**
	 * lookup each URI for a nodegroup
	 * @param importNg
	 * @param record
	 * @throws Exception
	 */
	private void lookupAllUris(NodeGroup importNg, ArrayList<String> record) throws Exception {
		// do URI lookups first
		for (int i=0; i < importNg.getNodeCount(); i++) {
			if (this.lookupMappings.containsKey(i)) {
				String uri = this.lookupUri(i, record);
				importNg.getNode(i).setInstanceValue(uri);
			}
		}	
	}
	
	/**
	 * Look up a URI.  Checking URICache first, and saving results to URICache.
	 * If URI is not found and MODE_CREATE, then it returns UriCache.NOT_FOUND
	 *    and caller is responsible for generating not found guids.
	 * @param ImportMappings
	 * @return  uri or URICache.NOT_FOUND
	 * @throws Exception - error, or not found and NO_CREATE, or found multiple
	 */
	private String lookupUri(int nodeIndex, ArrayList<String> record) throws Exception {
		
		// create a new nodegroup copy:  
		ArrayList<String> builtStrings = new ArrayList<String>();
		
		// Build the mapping results into builtStrings
		for (ImportMapping mapping : this.lookupMappings.get(nodeIndex)) {
			String builtStr = mapping.buildString(record);
			builtStrings.add(builtStr);
		}
				
		// return quickly if answer is already cached
		String cachedUri = this.uriCache.getUri(nodeIndex, builtStrings);
		
		// if already cached
		if (cachedUri != null) {
		
			// if "createIfMissing" and has a mapping and is a generated (not looked up) URI
			if (this.lookupMode.containsKey(nodeIndex) && 
					this.lookupMode.get(nodeIndex).equals(ImportSpecHandler.LOOKUP_MODE_CREATE) && 
					this.getImportMapping(nodeIndex, -1) != null &&
					this.uriCache.isGenerated(cachedUri)) {
				
				// make sure that two different records aren't creating different values for same URI
				ImportMapping map = this.getImportMapping(nodeIndex, -1);
				String newUri = map.buildString(record);
				
				if (! newUri.equals(cachedUri)) {
					if (newUri.equals(UriCache.NOT_FOUND))    { newUri = "<GUID>"; }
					if (cachedUri.equals(UriCache.NOT_FOUND)) { cachedUri = "<GUID>"; }
					throw new Exception("Can't create a URI with two different values: " + cachedUri + " and " + newUri);
				}
			}
			
			// survived the check: return the cached value
			return cachedUri;
			
		} else {
			// get the nodegroup and do the lookup
			NodeGroup lookupNodegroup = NodeGroup.getInstanceFromJson(this.lookupNodegroupsJson.get(nodeIndex)); 
			
			// loop through lookupMappings and add constraints to the lookupNodegroup
			int i = 0;
			for (ImportMapping mapping : this.lookupMappings.get(nodeIndex)) {

				String builtString = builtStrings.get(i++);
				Node node = lookupNodegroup.getNode(mapping.getLookupNodeIndex());
				
				// check for empties
				if (builtString == null || builtString.isEmpty()) {
					throw new Exception("URI Lookup field is empty for node " + node.getSparqlID());
				}
				
				if (mapping.isNode()) {
					if (this.oInfo.classIsEnumeration(node.getFullUriName())) {
						builtString = this.oInfo.getMatchingEnumeration(node.getFullUriName(), builtString);
					}
					
					ValuesConstraint v = new ValuesConstraint(node, builtString);
					node.setValueConstraint(v);
					
				} else {
					PropertyItem prop = node.getPropertyItem(mapping.getPropItemIndex());
					ValuesConstraint v = new ValuesConstraint(prop, builtString);
					prop.setValueConstraint(v);
				}
			}
			
			String query = lookupNodegroup.generateSparql(AutoGeneratedQueryTypes.QUERY_DISTINCT, false, 0, null);
			
			// Run the query
			// make this thread-safe
			SparqlEndpointInterface safeEndpoint = this.nonThreadSafeEndpoint.copy();
			TableResultSet res = (TableResultSet) safeEndpoint.executeQueryAndBuildResultSet(query, SparqlResultTypes.TABLE);
			res.throwExceptionIfUnsuccessful();
			Table tab = res.getTable();
			
			// Check and return results
			if (tab.getNumRows() > 1) {
				// multiple found: error
				throw new Exception("URI lookup found multiple URI's");
				
			} else if (tab.getNumRows() == 0) {
				// zero found
				if (this.getLookupMode(nodeIndex).equals(LOOKUP_MODE_NO_CREATE)) {
					throw new Exception("URI lookup failed.");
					
				} else {
					// set URI to NOT_FOUND
					ImportMapping m = this.getImportMapping(nodeIndex, -1);
					this.uriCache.setUriNotFound(nodeIndex, builtStrings, (m == null) ? null : m.buildString(record));
					return UriCache.NOT_FOUND;
				}
				
			} else {
				// 1 found:  cache and return
				String uri = tab.getCell(0,0);
				this.uriCache.putUri(nodeIndex, builtStrings, uri);
				return uri;
			}
		}
	}
	
	/**
	 * Find an import mapping for the given node and property
	 * @param nodeIndex
	 * @param propIndex - can be -1 for none
	 * @return ImportMapping or null
	 */
	private ImportMapping getImportMapping(int nodeIndex, int propIndex) {
		for (int i=0; i < this.importMappings.length; i++) {
			if (this.importMappings[i].getImportNodeIndex() == nodeIndex && this.importMappings[i].getPropItemIndex() == propIndex ) {
				return this.importMappings[i];
			}
		}
		return null;
	}
	
	/**
	 * Find lookupMode.   If not set, then use the default.
	 * @param nodeIndex
	 * @return - always a valid mode string
	 */
	private String getLookupMode(int nodeIndex) {
		String mode = this.lookupMode.get(nodeIndex);
		if (mode == null) {
			return ImportSpecHandler.LOOKUP_MODE_NO_CREATE;   // default mode
		} else {
			return mode;
		}
	}
	
	/**
	 * Return a pointer to every PropertyItem in ng that is used in the import spec
	 *   mapping or URILookup
	 * @param ng
	 * @return
	 */
	public ArrayList<PropertyItem> getUndeflatablePropItems(NodeGroup ng) {

		ArrayList<PropertyItem> ret = new ArrayList<PropertyItem>();
		
		if (this.importMappings != null) {
			for (int i=0; i < this.importMappings.length; i++) {
				if (this.importMappings[i].isProperty()) {
					ImportMapping m = this.importMappings[i];
					PropertyItem pItem = ng.getNode(m.getImportNodeIndex()).getPropertyItem(m.getPropItemIndex());
					ret.add(pItem);
				}
			}
		}
		
		if (this.lookupMappings != null) {
			for (int i : this.lookupMappings.keySet()) {
				ArrayList<ImportMapping> mapList = this.lookupMappings.get(i);
				for (ImportMapping m : mapList) {
					if (m.isProperty()) {
						PropertyItem pItem = ng.getNode(m.getImportNodeIndex()).getPropertyItem(m.getPropItemIndex());
						if (!ret.contains(pItem)) {
							ret.add(pItem);
						}
					}
				}
			}
		}
		return ret;
	}
	
	
	/**
	 * Get all column names that were actually used in mappings.
	 * @return
	 */
	public String[] getColNamesUsed(){
		return this.colsUsedKeys;
	}

	
	private NodeGroup setURIsForNullNodes(NodeGroup ng) throws Exception{
		for(Node n : ng.getNodeList()){
			if(n.getInstanceValue() == null ){
				n.setInstanceValue(this.uriResolver.getInstanceUriWithPrefix(n.getFullUriName(), UUID.randomUUID().toString()) );
			}
		}
		// return the patched results.
		return ng;
	}
	
	/**
	 * Does LOOKUP_MODE_CREATE appear anywhere in the ImportSpec
	 * @return
	 */
	public boolean containsLookupModeCreate() {
		for (Integer key : this.lookupMode.keySet()) {
			if (this.getLookupMode(key).equals(ImportSpecHandler.LOOKUP_MODE_CREATE)) {
				return true;
			}
		}
		return false;
	}

	public static String validateDataType(String input, String expectedSparqlGraphType) throws Exception{
		return validateDataType(input, expectedSparqlGraphType, false);
	}
	
	/**
	 * Validates and in some cases modifies/reformats an input based on type
	 * @param input
	 * @param expectedSparqlGraphType - last part of the type, e.g. "float"
	 * @param skipValidation - if True, perform modifications but no validations
	 * @return - valid value
	 * @throws Exception - if invalid
	 */
	@SuppressWarnings("deprecation")
	public static String validateDataType(String input, String expectedSparqlGraphType, Boolean skipValidation) throws Exception{
		 
		 //   from the XSD data types:
		 //   string | boolean | decimal | int | integer | negativeInteger | nonNegativeInteger | 
		 //   positiveInteger | nonPositiveInteger | long | float | double | duration | 
		 //   dateTime | time | date | unsignedByte | unsignedInt | anySimpleType |
		 //   gYearMonth | gYear | gMonthDay;
		 
		// 	  added for the runtimeConstraint:
		//	  NODE_URI
		
		/**
		 *  Please keep the wiki up to date
		 *  https://github.com/ge-semtk/semtk/wiki/Ingestion-type-handling
		 */
		
		String myType = expectedSparqlGraphType.toLowerCase();
		
		// perform validations that change the input
		switch (myType) {
		case "string":
			return SparqlToXUtils.safeSparqlString(input);
		case "datetime":
			try{				 
				return Utility.getSPARQLDateTimeString(input);				 				 
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}	
		case "date":
			try{
				return Utility.getSPARQLDateString(input);				 
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
		}
		
		// efficiency circuit-breaker
		if (skipValidation) {
			return input;
		}
		
		// perform plain old validations
		switch(myType) {
		case "node_uri":
			try {
			// check that this looks like a URI
				new URI(input);
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause: " + e.getMessage());
			}
			break;		
		case "boolean":
			try{
				Boolean.parseBoolean(input);
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
			break;
		case "decimal":
			try{
				Double.parseDouble(input);
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
			break;
		case "int":
			try{
				Integer.parseInt(input);
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
			break;
		case "integer":
			try {
				Integer.parseInt(input);
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
			break;
		case "long":
			try {
				Long.parseLong(input);
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
			break;
		case "float":
			try{
				Float.parseFloat(input);
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
			break;
		case "double":
			try {
				Double.parseDouble(input);
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
			break;
		case "time":
			try{
				Time.parse(input);
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
			break;
		case "negativeinteger":
			try{
				int test = Integer.parseInt(input);
				if(test >= 0){
					throw new Exception("value in model is negative integer. non-negative integer given as input");
					}
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
			break;
		case "nonNegativeinteger":
			try{
				int test = Integer.parseInt(input);
				if(test < 0){
					throw new Exception("value in model is nonnegative integer. negative integer given as input");
				}
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
			break;
		case "positiveinteger":
			try{
				int test = Integer.parseInt(input);
				if(test <= 0){
					throw new Exception("value in model is positive integer. integer <= 0 given as input");
				} 
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
			break;
		case "nonpositiveinteger":
			try{
				int test = Integer.parseInt(input);
				if(test > 0){
					throw new Exception("value in model is nonpositive integer. integer > 0 given as input");
				}
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
			break;

		case "duration":
			// not sure how to check this one. this might not match the expectation from SADL
			try{
				Duration.parse(input);
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
			break;
		case "unsignedbyte":
			try{
				Byte.parseByte(input);
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
			break;
		case "unsignedint":
			try{
				Integer.parseUnsignedInt(input);
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
			break;
		case "anySimpleType":
			// do nothing. 
			break;
		case "gyearmonth":
			try{
				String[] all = input.split("-");
				// check them all
				if(all.length != 2){ throw new Exception("year-month did not have two parts."); }
				if(all[0].length() != 4 && all[1].length() != 2){ throw new Exception("year-month format was wrong. " + input + " given was not YYYY-MM"); }
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
			break;
		case "gyear":
			try{
				if(input.length() != 4){ throw new Exception("year-month format was wrong. " + input + " given was not YYYY-MM"); }
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
			break;
		case "gmonthday":
			try {
				String[] all = input.split("-");
				// check them all
				if(all.length != 2){ throw new Exception("month-day did not have two parts."); }
				if(all[0].length() != 2 && all[1].length() != 2){ throw new Exception("month-day format was wrong. " + input + " given was not MM-dd"); }
			}
			catch(Exception e){
				throw new Exception("attempt to use value \"" + input + "\" as type \"" + expectedSparqlGraphType + "\" failed. assumed cause:" + e.getMessage());
			}
			break;

		default:
				// unknown types slip through un-validated.
		}
		
		return input;
	}
}
