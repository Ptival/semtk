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


package com.ge.research.semtk.belmont;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.ge.research.semtk.load.utility.ImportSpec;
import com.ge.research.semtk.ontologyTools.OntologyClass;
import com.ge.research.semtk.ontologyTools.OntologyInfo;
import com.ge.research.semtk.ontologyTools.OntologyName;
import com.ge.research.semtk.ontologyTools.OntologyProperty;
import com.ge.research.semtk.ontologyTools.OntologyRange;
import com.ge.research.semtk.ontologyTools.ValidationException;

//the nodes which represent any given entity the user/caller intends to manipulate. 

public class Node extends Returnable {
	
	private XSDSupportedType nodeType = XSDSupportedType.NODE_URI;
	
	// keeps track of our properties and collection 	
	private ArrayList<PropertyItem> props = new ArrayList<PropertyItem>();
	private ArrayList<NodeItem> nodes = new ArrayList<NodeItem>();
	
	// basic information required to be a node
	private String fullURIname = null;
	private String instanceValue = null;
	private boolean instanceLookedUp = false;
	private NodeGroup nodeGroup = null;
	
	private NodeDeletionTypes deletionMode = NodeDeletionTypes.NO_DELETE;
	
	// Left-over from confused port from javascript
	public Node(String name, ArrayList<PropertyItem> p, ArrayList<NodeItem> n, String URI, NodeGroup ng){
		// just create the basic node.
		this.fullURIname = URI;
		if(n != null){ this.nodes = n;}
		if(p != null){ this.props = p;}
		this.nodeGroup = ng;
		
		// add code to get the sparqlID
		this.sparqlID = BelmontUtil.generateSparqlID(name, this.nodeGroup.getAllVariableNames());
	}
	
	public Node(String jsonStr, NodeGroup ng) throws Exception{
		// create the JSON Object we need and then call the other constructor. 
		this((JSONObject)(new JSONParser()).parse(jsonStr), ng);
	}
	
	public Node(JSONObject nodeEncoded, NodeGroup ng) throws Exception{
		// create a new node from JSON, assuming everything is sane. 
		this.nodeGroup = ng;
		
		this.updateFromJson(nodeEncoded);
	}
	
	public Node(JSONObject nodeEncoded, NodeGroup ng, OntologyInfo inflateOInfo) throws Exception{
		// create a new node from JSON, assuming everything is sane. 
		this.nodeGroup = ng;
		
		this.updateFromJson(nodeEncoded);
		this.inflateAndValidate(inflateOInfo);

	}
	
	public void setFullURIname(String fullURIname) {
		this.fullURIname = fullURIname;
	}

	@SuppressWarnings("unchecked")
	public JSONObject toJson() {
		return this.toJson(null);
	}
	
	/**
	 *
	 * @param dontDeflatePropItems - list of PropertyItems which should not be deflated
	 * @return
	 */
	public JSONObject toJson(ArrayList<PropertyItem> dontDeflatePropItems) {
		// return a JSON object of things needed to serialize
		JSONObject ret = new JSONObject();
		JSONArray jPropList = new JSONArray();
		JSONArray jNodeList = new JSONArray();
		
		// NOTE removed optional subclassNames
		
		// add properties
		for (int i = 0; i < this.props.size(); i++) { 
			PropertyItem p = this.props.get(i);
			// if compressFlag, then only add property if returned or constrained
			if (dontDeflatePropItems == null || p.isUsed() || dontDeflatePropItems.contains(p)) {
				jPropList.add(p.toJson());
			}
		}
		
		// add nodes
		for (int i = 0; i < this.nodes.size(); i++) {
			// if we're deflating, only add connected nodes
			if (dontDeflatePropItems == null || this.nodes.get(i).isUsed()) {
				jNodeList.add(this.nodes.get(i).toJson());
			}
		}
				
		ret.put("propList", jPropList);
		ret.put("nodeList", jNodeList);
		ret.put("fullURIName", this.fullURIname);
		ret.put("valueConstraint", this.constraints != null ? this.constraints.toString(): "");
		
		this.addReturnableJson(ret);
		
		ret.put("instanceValue", this.instanceValue);
		ret.put("deletionMode", this.deletionMode.name());
		
		return ret;
	}
	
	/**
	 * Instance was looked up and found
	 * @return
	 */
	public boolean isInstanceLookedUp() {
		return instanceLookedUp;
	}

	public void setInstanceLookedUp(boolean instanceLookedUp) {
		this.instanceLookedUp = instanceLookedUp;
	}

	/**
	 * Inflate (add any missing properties) and validate against model
	 * This legacy version throws an Exception on the first model error.
	 * @param oInfo
	 * @throws Exception
	 */
	public void inflateAndValidate(OntologyInfo oInfo) throws Exception {
		ArrayList<String> modelErrList = new ArrayList<String>();
		ArrayList<NodeGroupItemStr> itemStrList = new ArrayList<NodeGroupItemStr>();
		ArrayList<String> warningList = new ArrayList<String>();
		
		this.inflateAndValidate(oInfo, null, modelErrList, itemStrList, warningList);
		
		if (modelErrList.size() > 0) {
			throw new ValidationException(modelErrList.get(0));
		}
	}
	
	/**
	 * Expand props to full set of properties for this classURI in oInfo 
	 * and validates all props
	 * 
	 * If modelErrList is given, an invalid nodegroup will be expanded and returned.
	 * Otherwise an exception is thrown at the first model error.
	 * 
	 * All ERRORS are inflated into the nodegroup in their bad state
	 * All WARNINGS are fixed
	 * 
	 * Expanding an invalid node:
	 * 
	 * 		Type     * 1a ERROR unknown URI
	 * 	
	 * 		PropItem * 2a ERROR - bad uri (domain) if constrained
	 * 				 * 2b WARNING - bad uri (domain) if not constrained
	 * 				 * 3a ERROR - wrong range if constrained
	 * 				 * 3b WARNING - wrong range if not constrained
	 *
	 * 		NodeItem * 4a ERROR bad uri (domain)                             items:  null target + all connected targets
	 *               * 4b WARNING bad uri (domain) but unconnected
	 *               
	 * 				 * 5a ERROR  wrong range and connected to any illegal    items:  null target + all connected targets
	 *               * 5b WARNING wrong range but unconnected
	 *               * 5c WARNING wrong range but connected nodes all legal to model so fix is easy
	 *               
	 * 				 * 6 ERROR connected to nodes that violate the (correct) range   items: targets that don't match range
	 *              
	 * 
	 * @param oInfo - if non-null then inflate and validate
	 * @param importSpec - if non-null then check for isUsed() before refusing to inflate invalid
	 * @param modelErrList - if null, throw exception on first error : else collect a list of text model errors
	 * @param itemStrList - if null, nothing, else list will have a NodeGroupItemStr for each invalid item found
	 * @return
	 * @throws Exception
	 */
	public void inflateAndValidate(OntologyInfo oInfo, ImportSpec importSpec, ArrayList<String> modelErrList, ArrayList<NodeGroupItemStr> itemStrList, ArrayList<String> warningList) throws Exception {
		if (oInfo == null) { return; }
		
		ArrayList<PropertyItem> inflatedPItems = new ArrayList<PropertyItem>();
		ArrayList<NodeItem> inflatedNItems = new ArrayList<NodeItem>();
		
		// build hash of suggested properties for this class
		HashMap<String, PropertyItem> inputPItemHash = new HashMap<>();
		for (PropertyItem p : this.props) {
			inputPItemHash.put(p.getUriRelationship(), p);
		}
		
		// build hash of suggested nodes for this class
		HashMap<String, NodeItem> inputNItemHash = new HashMap<>();
		for (NodeItem n : this.nodes) {
			// silently skip legacy node items that weren't compressed properly
			if (n.getUriConnectBy() == null || n.getUriConnectBy().isEmpty()) {
				if (n.getConnected()) {
					// but fail if the legacy node is really messed up.
					// If this haunts us and you're revisiting this code:
					// Perhaps default the the Node's base URI plus the keyname == the UriConnectby
					// Maybe this should be done when the json is loaded?  Or maybe here is ok.
					throw new Exception("Nodegroup json error: Node item is connected by has empty UriConnectBy " + n.getKeyName());
				}
			} else {
				inputNItemHash.put(n.getUriConnectBy(), n);
			}
		}
		
		// get oInfo's version of the property list
		OntologyClass ontClass = oInfo.getClass(this.getFullUriName());
		ArrayList<OntologyProperty> ontProps = new ArrayList<OntologyProperty>();
		if (ontClass == null) {
			ontClass = new OntologyClass(this.fullURIname);
			// ERROR: node class is unknown.  
			// In message to user, use Binding even if it isn't unique.  SparqlID may not make sense to user.
			String msg = this.getBindingOrSparqlID() + "'s class does not exist in the model:  " + this.getFullUriName();
			modelErrList.add(msg);
			itemStrList.add(new NodeGroupItemStr(this));
		} else {
			ontProps = oInfo.getInheritedProperties(ontClass);
		}
		
		// loop through oInfo's version
		// Remember: oProp can be propItem or nodeItem
		for (OntologyProperty ontProp : ontProps) {
			String ontPropURI = ontProp.getNameStr(false);			
			String localPropURI = ontProp.getNameStr(true);

			if (inputPItemHash.containsKey(ontPropURI)) {
				// if input nodegroup contains this property
				
				PropertyItem propItem = inputPItemHash.get(ontPropURI);
				if (! propItem.getValueTypeURI().equals(ontProp.getRangeStr())) {
					String modelRangeStr = ontProp.getRangeStr();
					String propRangeStr = propItem.getValueTypeURI();
					String abbrevURI[] = abbreviateURIs(propRangeStr, modelRangeStr);
					
					if (propItem.getConstraints() != null || (importSpec != null && importSpec.isUsed(this.getSparqlID(), ontPropURI))) {
						// ERROR property range doesn't match
						String msg = this.getBindingOrSparqlID() + " property " + localPropURI + "'s range of " + abbrevURI[0] + " doesn't match model: " + abbrevURI[1];
						modelErrList.add(msg);
						itemStrList.add(new NodeGroupItemStr(this, propItem));
					} else {
						// WARNING property range doesn't match
						String msg = this.getBindingOrSparqlID() + " property " + localPropURI + "'s range of " + abbrevURI[0] + " changed to: " + abbrevURI[1];
						warningList.add(msg);
						// fix range
						if (! oInfo.containsClass(modelRangeStr)) {
							// normal fix: property
							propItem.changeValueType(XSDSupportedType.getMatchingValue(ontProp.getRangeStr(true)));
						} else {
							// change to nodeItem
							 NodeItem nodeItem = new NodeItem(   
									 ontProp.getNameStr(false), 
                                     ontProp.getRangeStr(true),
                                     ontProp.getRangeStr(false)
                                     );
							 inflatedNItems.add(nodeItem);
							 propItem = null;
						}
						
					}
				}
				
				// add the propItem, unless emergency switch to nodeItem
				if (propItem != null) {
					inflatedPItems.add(propItem);
				}
				
				inputPItemHash.remove(ontPropURI);
				
		
			} else if (inputNItemHash.containsKey(ontPropURI)) {
				// nodeItem is used
				
				NodeItem nodeItem = inputNItemHash.get(ontPropURI);
				String nRangeStr = nodeItem.getUriValueType();
				String nRangeAbbr = nodeItem.getValueType();
			    OntologyClass nRangeClass = oInfo.getClass(nRangeStr);
				String correctRangeStr = ontProp.getRangeStr();
				String correctRangeAbbrev = ontProp.getRangeStr(true);
				OntologyClass correctRangeClass = oInfo.getClass(correctRangeStr);
				
				// is range incorrect
				// PEC HERE: this isn't right.  They need to be equal.
				boolean rangeErrFlag = ! nRangeStr.equals(correctRangeStr);
				
				// 6. check for targets with classes that aren't a type of the CORRECT range
				ArrayList<Node> targetErrList = new ArrayList<Node>();
				if (nodeItem.getConnected()) {
					
					for (Node target : nodeItem.getNodeList()) {
						OntologyClass targetClass = oInfo.getClass(target.getUri());
						
						if (!oInfo.classIsA(targetClass, correctRangeClass)) {
							targetErrList.add(target);
						}
					}
				} 
				
				// range of nodeItem is wrong
				if (rangeErrFlag) {
					String abbrevURI[] = abbreviateURIs(nRangeStr, correctRangeStr);

					if (targetErrList.size() == 0) {
						// 5b 5c  Fix nodeitem range
						warningList.add( this.getBindingOrSparqlID() + " edge " + localPropURI + "'s range of " + abbrevURI[0] + " changed to: " + abbrevURI[1]);
						if (oInfo.containsClass(correctRangeStr)) {
							nodeItem.changeUriValueType(correctRangeStr);
						} else {
							// "emergency" switch to a propItem
							PropertyItem propItem = new PropertyItem(	
									ontProp.getNameStr(true), 
									ontProp.getRangeStr(true), 
									ontProp.getRangeStr(false),
									ontProp.getNameStr(false));
							
							
							inflatedPItems.add(propItem);
							nodeItem = null;
						}
					} else {
						
						// 5a else bad connections
						modelErrList.add( this.getBindingOrSparqlID() + " edge " + localPropURI + "'s range of " + abbrevURI[0] + " doesn't match to model: " + abbrevURI[1]);

						// all targets are bad items, so is generic "null target"
						itemStrList.add(new NodeGroupItemStr(this, nodeItem, null));
						for (Node target : nodeItem.getNodeList()) {
							itemStrList.add(new NodeGroupItemStr(this, nodeItem, target));
						}
					}
					
				} else {
					// range is ok
					
					// somewhat randomly, check for keyname errors
					if (!nRangeAbbr.equals(new OntologyName(nRangeStr).getLocalName())) {
						warningList.add(this.getBindingOrSparqlID() + " node property property " + localPropURI + "'s abbrev " + nRangeAbbr + " corrected to match model: " + ontProp.getRangeStr(true));
						nodeItem.setValueType(ontProp.getRangeStr(true));
					}
				
				
					// report any bad connections 
					for (Node target : targetErrList) {
						modelErrList.add( this.getBindingOrSparqlID() + " edge " + localPropURI + "'s range of " + correctRangeAbbrev + " does not allow connection to " + target.getBindingOrSparqlID());
						itemStrList.add(new NodeGroupItemStr(this, nodeItem, target));
					}
					
				}
				
				// add the nodeItem unless it was "emergency" switched to a property
				if (nodeItem != null) {
					inflatedNItems.add(nodeItem);
				}
				inputNItemHash.remove(ontPropURI);
			
			} else if (!oInfo.containsClass(ontProp.getRangeStr())) {
				// Range class is not found: property deflated out of the nodegroup: inflate
				PropertyItem propItem = new PropertyItem(	
						ontProp.getNameStr(true), 
						ontProp.getRangeStr(true), 
						ontProp.getRangeStr(false),
						ontProp.getNameStr(false));
				
				
				inflatedPItems.add(propItem);
			
			} else {
                // nodeitem deflated out of nodegroup: inflate
                NodeItem nodeItem = new NodeItem(   ontProp.getNameStr(false), 
                                                    ontProp.getRangeStr(true),
                                                    ontProp.getRangeStr(false)
                                                    );
                inflatedNItems.add(nodeItem);
            }
		}
		
		// Check if anything is left in propItemHash, meaning it wasn't found in the model
		for (String key : inputPItemHash.keySet()) {
			PropertyItem propItem = inputPItemHash.get(key);
			if (propItem.isUsed() || (importSpec != null && importSpec.isUsed(this.getSparqlID(), key))) {
				String msg = this.getBindingOrSparqlID() + " has property that isn't in the model: " + key;
				modelErrList.add(msg);
				itemStrList.add(new NodeGroupItemStr(this, propItem));
	
	            // add it anyway
	            inflatedPItems.add(inputPItemHash.get(key));
			} else {
				warningList.add("Deleted invalid and unused property: " + this.getBindingOrSparqlID() + "->" + key);
			}
			
        }

		// Check if anything is left in nodeItemHash, meaning it wasn't found in the model
        for (String key : inputNItemHash.keySet()) {
        	NodeItem nodeItem = inputNItemHash.get(key);
        	if (nodeItem.isUsed()) {
	        	String msg = this.getBindingOrSparqlID() + " has edge that isn't in the model: " + key;
	        	modelErrList.add(msg);
				NodeItem nItem = inputNItemHash.get(key);
				if (nItem.getConnected()) {
					itemStrList.add(new NodeGroupItemStr(this, nodeItem, null));
					for (Node target : nItem.getNodeList() ) {
						// add every nodeItem/target combo
						itemStrList.add(new NodeGroupItemStr(this, nodeItem, target));
					}
				} else {
					// add nodeItem with null target: not likely since isUsed() means isConnected()
					itemStrList.add(new NodeGroupItemStr(this, inputNItemHash.get(key), null));
				}
				
	            // add it anyway
	            inflatedNItems.add(inputNItemHash.get(key));
        	} else {
        		warningList.add("Removed invalid unused edge that isn't in the model" + this.getBindingOrSparqlID() + "->" + key);
        	}
        }
		
		this.props = inflatedPItems;
		this.nodes = inflatedNItems;
		
	}
	
	/**
	 * Return local names unless they are equal, return full names
	 * @param uri1
	 * @param uri2
	 * @return
	 */
	private static String[] abbreviateURIs(String uri1, String uri2) {
		String abbrev1 = new OntologyName(uri1).getLocalName();
		String abbrev2 = new OntologyName(uri2).getLocalName();
		if (abbrev1.equals(abbrev2)) {
			return new String [] {uri1, uri2};
		} else {
			return new String [] {abbrev1, abbrev2};
		}
	}
	
	/**
	 * Validates that all nodeItems and propertyItems exist in the model
	 * @param oInfo
	 * @throws Exception
	 */
	public void validateAgainstModel(OntologyInfo oInfo) throws Exception {
		OntologyClass oClass = oInfo.getClass(this.fullURIname);
		
		if (oClass == null) {
			throw new ValidationException("Class URI does not exist in the model: " + this.fullURIname);
		}
		
		// build hash of ontology properties for this class
		HashMap<String, OntologyProperty> oPropHash = new HashMap<>();
		for (OntologyProperty op : oInfo.getInheritedProperties(oClass)) {
			oPropHash.put(op.getNameStr(), op);
		}
		
		// check each property's URI and range
		for (PropertyItem myPropItem : this.props) {
			// domain
			if (! oPropHash.containsKey(myPropItem.getUriRelationship())) {
				throw new ValidationException(String.format("Node %s contains property %s which does not exist in the model",
									this.getSparqlID(), myPropItem.getUriRelationship()));
			}
			
			// range
			OntologyRange oRange = oPropHash.get(myPropItem.getUriRelationship()).getRange();
			if (! oRange.getFullName().equals(myPropItem.getValueTypeURI())) {
				throw new ValidationException(String.format("Node %s, property %s has type %s which doesn't match %s in model", 
									this.getSparqlID(), myPropItem.getUriRelationship(), myPropItem.getValueTypeURI(), oRange.getFullName()));
			}
		}
		
		// check node items
		for (NodeItem myNodeItem : this.nodes) {
			if (myNodeItem.getConnected()) {
				// domain
				if (! oPropHash.containsKey(myNodeItem.getUriConnectBy())) {
					throw new ValidationException(String.format("Node %s contains node connection %s which does not exist in the model",
										this.getSparqlID(), myNodeItem.getUriConnectBy()));
				}
				
				// range
				// Raghava's bug is right here
				OntologyProperty oProp = oPropHash.get(myNodeItem.getUriConnectBy());
				OntologyRange oRange = oProp.getRange();
				if (! myNodeItem.getUriValueType().equals(oRange.getFullName())) {
					ArrayList<OntologyProperty> d = oInfo.getInheritedProperties(oClass);
					throw new ValidationException(String.format("Node %s contains node connection %s with type %s which doesn't match %s in model", 
										this.getSparqlID(), myNodeItem.getUriConnectBy(), myNodeItem.getUriValueType(), oRange.getFullName()));
				}
				
				// connected node types
				for (Node n : myNodeItem.getNodeList()) {
					OntologyClass rangeClass = oInfo.getClass(oRange.getFullName());
					OntologyClass myNodeClass = oInfo.getClass(n.getFullUriName());
					if (!oInfo.classIsA(myNodeClass, rangeClass)) {
						throw new ValidationException(String.format("Node %s, node connection %s connects to node %s with type %s which isn't a type of %s in model", 
								this.getSparqlID(), myNodeItem.getUriConnectBy(), n.getSparqlID(), n.getFullUriName(), oRange.getFullName()));
					}
				}
			}
		}
	}
	
	/** 
	 * clear everything except names and connections between nodes
	 */
	public void reset() {
		this.instanceValue = null;
		this.isReturned = false;
		this.isBindingReturned = false;
		this.deletionMode = NodeDeletionTypes.NO_DELETE;
		this.constraints = null;
		this.isRuntimeConstrained = false;
		
		for (PropertyItem p : this.props) {
			p.reset();
		}
		
		for (NodeItem n : this.nodes) {
			n.reset();
		}
	}
	
	public void setSparqlID(String ID){
		if (this.constraints != null) {
			this.constraints.changeSparqlID(this.sparqlID, ID);
		}
		this.sparqlID = ID;
	}
	
	public void updateFromJson(JSONObject nodeEncoded) throws Exception{
		// blank existing 
		props = new ArrayList<PropertyItem>();
		nodes = new ArrayList<NodeItem>();
		fullURIname = null;
		instanceValue = null;		
		
		this.fromReturnableJson(nodeEncoded);

		// build all the parts we need from this incoming JSON Object...
		this.fullURIname = nodeEncoded.get("fullURIName").toString();
		
				
		// NOTE: removed OPTIONAL array of subclass names.
		try{
			this.instanceValue = nodeEncoded.get("instanceValue").toString();
		}
		catch(Exception E){ // the value was missing
			this.instanceValue = null;
		}
		
		try{
			this.setDeletionMode(NodeDeletionTypes.valueOf((String)nodeEncoded.get("deletionMode")));
		}
		catch(IllegalArgumentException iae) {
			throw iae;   // known bad enum exception
		}
		catch(NullPointerException enpe){
			this.setDeletionMode(NodeDeletionTypes.NO_DELETE);  // deletionMode is missing
		}
		catch(Exception ee){
			throw ee;   // other unexpected exception
		}
		
		// create the node items and property items.
		// nodeItems 
		JSONArray nodesToProcess = (JSONArray)nodeEncoded.get("nodeList");
		Iterator<JSONObject> nIt = nodesToProcess.iterator();
		while(nIt.hasNext()){
			this.nodes.add(new NodeItem(nIt.next(), this.nodeGroup));
		}
		
		// propertyItems
		JSONArray propertiesToProcess = (JSONArray)nodeEncoded.get("propList");
		Iterator<JSONObject> pIt = propertiesToProcess.iterator();
		while(pIt.hasNext()){
			this.props.add(new PropertyItem(pIt.next()));
		}
	}
	
	public NodeItem setConnection(Node curr, String connectionURI) throws Exception {
		return this.setConnection(curr,  connectionURI, NodeItem.OPTIONAL_FALSE, false);
	}
	
	public NodeItem setConnection(Node curr, String connectionURI, int opt) throws Exception {
		return this.setConnection(curr,  connectionURI, opt, false);
	}
	
	public NodeItem setConnection(Node curr, String connectionURI, Boolean markForDeletion) throws Exception {
		return this.setConnection(curr,  connectionURI, NodeItem.OPTIONAL_FALSE, markForDeletion);
	}
	
	public NodeItem setConnection(Node node, String connectionURI, int opt, Boolean markedForDeletion) throws Exception {
		// create a display name. 
		String connectionLocal = new OntologyName(connectionURI).getLocalName();

		// actually set the connection. 
		for(int i = 0; i < this.nodes.size(); i += 1){
			NodeItem nItem = this.nodes.get(i);
			// did it match?
			if(nItem.getUriConnectBy().equals(connectionURI)){
				nItem.setConnected(true);
				nItem.setConnectBy(connectionLocal);
				nItem.setUriConnectBy(connectionURI);
				nItem.pushNode(node, opt, markedForDeletion);
				
				return nItem;
			}
		}
		throw new Exception("Internal error in SemanticNode.setConnection().  Couldn't find node item connection: " + this.getSparqlID() + "->" + connectionURI);
	}

	public ArrayList<PropertyItem> getReturnedPropertyItems() {
		ArrayList<PropertyItem> retval = new ArrayList<PropertyItem>();
		// spin through the list of values and add teh correct ones. 
		for(int i = 0; i < this.props.size(); i += 1){
			PropertyItem pi = this.props.get(i);
			if(pi.hasAnyReturn()){		// we are returning this one. add it to the list. 
				retval.add(pi);
			}
		}
		// return the list. 
		return retval;
	}	
	
	public ArrayList<PropertyItem> getPropsForSparql(Returnable forceReturn, AutoGeneratedQueryTypes qt) {
		ArrayList<PropertyItem> retval = new ArrayList<PropertyItem>();
		// spin through the list of values and add teh correct ones. 
		for(int i = 0; i < this.props.size(); i += 1){
			PropertyItem pi = this.props.get(i);
			if(pi.hasAnyReturn()){		// we are returning this one. add it to the list. 
				retval.add(pi);
			}
			else if(pi.getIsMarkedForDeletion() && qt != null && qt == AutoGeneratedQueryTypes.QUERY_DELETE_WHERE){
				retval.add(pi);		   // 
			}
			else if( pi.getConstraints() != null && pi.getConstraints() != "" && !pi.getConstraints().isEmpty() ){
				retval.add(pi);
			}
			else if(pi.equals(forceReturn)){
				retval.add(pi);
			}
		}
		// return the list. 
		return retval;
	}	
		
	
	
	public boolean checkConnectedTo(Node nodeToCheck) {
		for (NodeItem n : nodes) {
			if (n.getConnected()) {
				ArrayList<Node> nodeList = n.getNodeList();
				for (Node o : nodeList) {
					if (o.getSparqlID().equals(nodeToCheck.getSparqlID())) {
						return true;
					}
				}
			}
		}	
		
		return false;
	}
	
	public ArrayList<Node> getConnectedNodes() {
		
		ArrayList<Node> connectedNodes = new ArrayList<Node>();
		
		for (NodeItem ni : this.nodes) {
			if (ni.getConnected()) {
				connectedNodes.addAll(ni.getNodeList());
			}
		}
		
		return connectedNodes;
	}

	/**
	 * 
	 * @return value constraint string, which might be "" but not null
	 */
	public String getValueConstraintStr() {
		return this.constraints != null ? this.constraints.toString() : "";
	}
	
	public void setValueConstraint(ValueConstraint v) {
		this.constraints = v;
	}
	
	public void addValueConstraint(String vc) {
		if (this.constraints == null) {
			this.constraints = new ValueConstraint(vc);
		} else {
			this.constraints.addConstraint(vc);
		}
	}
	
	public String getUri(boolean localFlag) {
		if (localFlag) {
			return new OntologyName(this.getFullUriName()).getLocalName();
		} else {
			return this.getFullUriName();
		}
	}
	public String getUri() {
		return this.getFullUriName();
	}
	
	public String getFullUriName() {
		return fullURIname;
	}

	public boolean isUsed(boolean instanceOnly) {
		if (instanceOnly) {
			return this.hasInstanceData();
		} else {
			return this.isUsed();
		}
	}
	
	public boolean isUsed() {
		if (this.hasAnyReturn() || this.constraints != null || this.instanceValue != null || this.isRuntimeConstrained || this.deletionMode != NodeDeletionTypes.NO_DELETE) {
			return true;
		}
		for (PropertyItem item : this.props) {
			if (item.isUsed()) {
				return true;
			}
		}
		return false;	
	}
	
	public boolean hasInstanceData() {
		if (this.instanceValue != null) {
			return true;
		}
		for (PropertyItem item : this.props) {
			if (item.getInstanceValues().size() > 0) {
				return true;
			}
		}
		return false;	
	}
	 
	public ArrayList<String> getSparqlIDList() {
		// list all sparqlID's in use by this node
		ArrayList<String> ret = new ArrayList<String>();
		ret.add(this.getSparqlID());
		
		for (PropertyItem p : this.props) {
			String s = p.getSparqlID();
			if (s != null && ! s.isEmpty()) {
				ret.add(s);
			}
		}
		return ret;
	}
	
	/**
	 * Find all nodeItems that connect to otherNode
	 * @param otherNode
	 * @return
	 */
	public ArrayList<NodeItem> getConnectingNodeItems(Node otherNode) {
		ArrayList<NodeItem> ret = new ArrayList<NodeItem>();
		
		// look through all my nodeItems
		for (NodeItem item : this.nodes) {
			if (item.getConnected()) {
				ArrayList<Node> nodeList = item.getNodeList();
				
				// does my connection point to otherNode
				for (Node node : nodeList) {
					if (node.getSparqlID().equals(otherNode.getSparqlID())) {
						ret.add(item);
					}
				}
			}
		}
		
		return ret;
	}

	/**
	 * Get all nodeItems that are connected
	 * @param otherNode
	 * @return
	 */
	public ArrayList<NodeItem> getConnectedNodeItems() {
		ArrayList<NodeItem> ret = new ArrayList<NodeItem>();
		
		// look through all my nodeItems
		for (NodeItem item : this.nodes) {
			if (item.getConnected()) {
				ret.add(item);
			}
		}
		
		return ret;
	}
	
	/**
	 * Get node item by predicateURI
	 * @param predicateURI
	 * @return might be null
	 */
	public NodeItem getNodeItem(String predicateURI) {
		for (NodeItem n : this.nodes) {
			if (n.getUriConnectBy().equals(predicateURI)) {
				return n;
			}
		}
		return null;
	}

	public ArrayList<PropertyItem> getPropertyItems() {
		return this.props;
	}
	
	public PropertyItem getPropertyItemBySparqlID(String currID){
		/* 
		 * return the given property item, if we can find it. if not, 
		 * just return null. 
		 * if an ID not prefixed with ? is passed, we are just going to add it.
		 */
		
		int i = this.getPropertyItemIndexBySparqlID(currID);
		if (i == -1) 
			return null;
		else
			return this.props.get(i);
	}
	
	public int getPropertyItemIndexBySparqlID(String currID) {
		if(currID != null && !currID.isEmpty() && !currID.startsWith("?")){
			currID = "?" + currID;
		}
		for (int i = 0; i < this.props.size(); i++) {
			if (this.props.get(i).getSparqlID().equals(currID)) {
				return i;
			}
		}
		return -1;
	}
	
	public PropertyItem getPropertyByKeyname(String keyname) {
		for (int i = 0; i < this.props.size(); i++) {
			if (this.props.get(i).getKeyName().equals(keyname)) {
				return this.props.get(i);
			}
		}
		return null;
	}
	
	public PropertyItem getPropertyItem(int i) {
		return this.props.get(i);
	}
	
	public int getPropertyIndexByURIRelation(String uriRel) {
		for (int i = 0; i < this.props.size(); i++) {
			if (this.props.get(i).getUriRelationship().equals(uriRel)) {
				return i;
			}
		}
		return -1;
	}

	public PropertyItem getPropertyByURIRelation(String uriRel) {
		for (int i = 0; i < this.props.size(); i++) {
			if (this.props.get(i).getUriRelationship().equals(uriRel)) {
				return this.props.get(i);
			}
		}
		return null;
	}
	
	public boolean ownsNodeItem(NodeItem nodeItem) {
		return this.nodes.contains(nodeItem);
	}
	
	public String getInstanceValue() {
		return this.instanceValue;
	}
	
	public void setInstanceValue(String value) {
		this.instanceValue = value;
	}
	
	public void setProperties(ArrayList<PropertyItem> p){
		if(p != null){
			this.props = p;
		}
	}
	
	public void setNodeItems(ArrayList<NodeItem> n){
		if(n!= null){
			this.nodes = n;
		}
	}
	
	public ArrayList<NodeItem> getNodeItemList() {
		return this.nodes;
	}
	
	
	public int countUnconstrainedReturns () {
		int ret = 0;
		
		if ((this.getIsReturned() || this.getIsBindingReturned()) && this.getValueConstraint() == null) {
			ret ++;
		}
		
		for (PropertyItem p : this.props) {
			if ((p.getIsReturned() || p.getIsBindingReturned()) && p.getValueConstraint() == null) {
				ret ++;
			}
		}
		
		return ret;
	}
	
	public int countConstrainedReturns () {
		int ret = 0;
		
		if ((this.getIsReturned() || this.getIsBindingReturned()) && this.getValueConstraint() != null) {
			ret ++;
		}
		
		for (PropertyItem p : this.props) {
			if ((p.getIsReturned() || p.getIsBindingReturned()) && p.getValueConstraint() != null) {
				ret ++;
			}
		}
		
		return ret;
	}
	
	public int getReturnedCount () {
		int ret = 0;
		if (this.getIsReturned()) {
			ret += 1;
		}
		if (this.getIsTypeReturned()) {
			ret += 1;
		}
		if (this.getIsBindingReturned()) {
			ret += 1;
		}
		for (PropertyItem p : this.getPropertyItems()) {
			if (p.getIsReturned()) {
				ret += 1;
			}
			if (p.getIsBindingReturned()) {
				ret += 1;
			}
		}
		return ret;
	}
	
	public ArrayList<PropertyItem> getConstrainedPropertyObjects() {
		ArrayList<PropertyItem> constrainedProperties = new ArrayList<PropertyItem>();
		
		for (PropertyItem pi : props) {
			if (pi.getConstraints() != null && !pi.getConstraints().isEmpty()) {
				constrainedProperties.add(pi);
			}
		}
		
		return constrainedProperties;
	}
	public XSDSupportedType getValueType(){
		return this.nodeType;
	}
	
	public void setDeletionMode(NodeDeletionTypes ndt){
		this.deletionMode = ndt;
	}
	
	public NodeDeletionTypes getDeletionMode(){
		return this.deletionMode;
	}

	public void rmPropItem(PropertyItem prop) {
		this.props.remove(prop);
	}

	public void rmNodeItem(NodeItem nItem) {
		this.nodes.remove(nItem);
	}

}
