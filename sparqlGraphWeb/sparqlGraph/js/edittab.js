/**
 ** Copyright 2017 General Electric Company
 **
 ** Authors:  Paul Cuddihy, Justin McHugh
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

/*
 *  EditTab - the GUI elements for building an ImportSpec
 *
 *  Basic programmer notes:
 *     ondragover
 *         - preventDefault will allow a drop
 *         - stopPropagation will stop the question from being bubbled up to a parent. 
 */

define([	// properly require.config'ed
         	'sparqlgraph/js/iidxhelper',
            'sparqlgraph/js/modaliidx',
            'sparqlgraph/js/ontologyinfo',
            
         	'jquery',
         	
			// shimmed
         	'sparqlgraph/dynatree-1.2.5/jquery.dynatree',
            'sparqlgraph/js/ontologytree',
            'sparqlgraph/js/belmont'
         	
		],

	function(IIDXHelper, ModalIidx, OntologyInfo, $) {
		
		
		//============ local object  EditTab =============
		var EditTab = function(treediv, canvasdiv, buttondiv, searchtxt) {
		    this.treediv = treediv;
            this.canvasdiv = canvasdiv;
            this.buttondiv = buttondiv;
            this.searchtxt = searchtxt;
            this.oInfo = null;
            this.oTree = null;
            
            this.initDynaTree();
            this.initButtonDiv();
        }
		
		EditTab.CONSTANT = "constant";

		
		EditTab.prototype = {
            
            /*
             * Initialize an empty dynatree
             */
			initDynaTree : function() {
                
                var treeSelector = "#" + this.treediv.id;
                
                $(treeSelector).dynatree({
                    onActivate: function(node) {
                        // A DynaTreeNode object is passed to the activation handler
                        // Note: we also get this event, if persistence is on, and the page is reloaded.
                        this.selectedNodeCallback(node);

                    }.bind(this),
                    onDblClick: function(node) {
                        // A DynaTreeNode object is passed to the activation handler
                        // Note: we also get this event, if persistence is on, and the page is reloaded.
                        // console.log("You double-clicked " + node.data.title);
                    }.bind(this),

                    dnd: {
                        onDragStart: function(node) {
                        /** This function MUST be defined to enable dragging for the tree.
                         *  Return false to cancel dragging of node.
                         */
                           // console.log("dragging " + gOTree.nodeGetURI(node));
                           // gDragLabel = gOTree.nodeGetURI(node);
                            return true;
                        }.bind(this),

                        onDragStop: function(node, x, y, z, aa) {
                           // console.log("dragging " + gOTree.nodeGetURI(node) + " stopped.");
                           // gDragLabel = null;
                        }.bind(this)
                    },	

                    persist: true,
                });
                     
                this.oTree = new OntologyTree($(treeSelector).dynatree("getTree")); 
            },
            
            initButtonDiv : function() {
                this.buttondiv.align="right";
                var but1 = IIDXHelper.createButton("Owl/Rdf", this.butDownloadOwl.bind(this));
                var but2 = IIDXHelper.createButton("SADL", this.butDownloadSadl.bind(this));
                var but3 = IIDXHelper.createButton("JSON", this.butDownloadJson.bind(this));

                var nbsp = "\u00A0";
                this.buttondiv.innerHTML = "<b>Download: ";
                this.buttondiv.appendChild(but1);
                this.buttondiv.appendChild(document.createTextNode(nbsp));
                this.buttondiv.appendChild(but2);
                this.buttondiv.appendChild(document.createTextNode(nbsp));
                this.buttondiv.appendChild(but3);
            },
            
            butDownloadOwl : function() {
                alert("Not Implemented");
            },
            
            butDownloadSadl : function() {
                alert("Not Implemented");
            },
            
            butDownloadJson : function() {
                IIDXHelper.downloadFile(JSON.stringify(this.oInfo.toJson(), null, 2), "oinfo.json", "application/json")
            },
            
            doSearch : function() {
                this.oTree.find(this.searchtxt.value);
            },
    
            doCollapse : function() {
                this.searchtxt.value="";
                this.oTree.collapseAll();
            },

            doExpand : function() {
                this.oTree.expandAll();
            },
            
            draw : function () {
                this.oTree.showAll();
            },
            
            setOInfo : function (oInfo) {
                this.oInfo = new OntologyInfo(oInfo.toJson());  // deepCopy
                this.oTree.setOInfo(this.oInfo);
            },
            
            selectedNodeCallback : function (node) {
                var name = node.data.value;
                if (name.indexOf("#") == -1) {
                    this.editNamespace(node);
                    
                } else if (this.oInfo.containsClass(name)) {
                    this.editClass(node);
                    
                } else if (this.oInfo.containsProperty(name)) {
                    this.editProperty(node);
                    
                } else {
                    throw new Error("Can't find selected node in oInfo.");
                }
            },
            
            editNamespace : function (node) {
                var name = node.data.value;
                this.canvasdiv.innerHTML = "Namespace<br>" + name;
            },
            
            editClass : function (node) {
                var oClass = this.oInfo.getClass(node.data.value);
                var namespace = oClass.getNamespaceStr();
                var name = oClass.getNameStr(true);
                
                this.canvasdiv.innerHTML = "";
                this.canvasdiv.style.margin="1ch";
                this.canvasdiv.innerHTML = "<legend>Class:</legend>";
                
                var nameForm = IIDXHelper.buildHorizontalForm();
                this.canvasdiv.appendChild(nameForm);
                nameForm.innerHTML = namespace + "# ";
                var nameInput = IIDXHelper.createTextInput("et_nameInput");
                nameInput.value = name;
                nameInput.onchange = this.onchangeClassName.bind(this, oClass, nameInput);
                nameForm.appendChild(nameInput);
                
                var annot = this.buildAnnotatorHTML(oClass);
                this.canvasdiv.appendChild(annot);
                
                this.canvasdiv.innerHTML += "<legend>Properties:</legend>";
                var propList = oClass.getProperties();
                for (var i=0; i < propList.length; i++) {
                    var oProp = propList[i];
                    
                    // PEC TODO: make this editable and non-garbage
                    this.canvasdiv.innerHTML += oProp.getNameStr() + " " + oProp.getRangeStr() + "<br>";
                    
                    var propAnnot = this.buildAnnotatorHTML(oProp);
                    this.canvasdiv.appendChild(propAnnot);
                }
            },
            
            buildAnnotatorHTML : function (oItem) {
                
                // create the dom and table
                var dom = document.createElement("div");
                var table = document.createElement("table");
                table.style.width="100%";
                dom.appendChild(table);
                var tr = document.createElement("tr");
                table.appendChild(tr);
                
                // top left cell is "labels"
                var td = document.createElement("td");
                td.style.verticalAlign = "top";
                td.style.width = "12ch";
                td.align="right";
                td.innerHTML = "labels: ";
                tr.appendChild(td);
                
                // top right will contain label inputs
                var labelsTd = document.createElement("td");
                var labelsId = IIDXHelper.getNextId("annotate");
                labelsTd.id = labelsId;
                tr.appendChild(labelsTd);
                var labelsForm = IIDXHelper.buildHorizontalForm();
                labelsTd.appendChild(labelsForm);
                
                tr = document.createElement("tr");
                table.appendChild(tr);
                
                // bottom left is "comments"
                td = document.createElement("td");
                td.style.verticalAlign = "top";
                td.align="right";
                td.innerHTML = "comments: ";
                tr.appendChild(td);
                
                // bottom right will contain comment inputs
                var commentsTd = document.createElement("td");
                var commentsId = IIDXHelper.getNextId("annotate");
                commentsTd.id = commentsId;
                tr.appendChild(commentsTd);
                
                // callback to add labels
                var addLabel = function(oItem, labelStr, td, before) {
                    var text = IIDXHelper.createTextInput(null, "input-medium");
                    text.value = labelStr;
                    text.onchange = this.onChangeLabel.bind(this, oItem, td);
                    td.insertBefore(text, before);
                    td.insertBefore(document.createTextNode(" "), before);
                }.bind(this);
                
                // add labels button
                var but1 = IIDXHelper.createIconButton("icon-plus");
                but1.onclick = addLabel.bind(this, oItem, "", labelsForm, but1);
                labelsForm.appendChild(but1);
                
                // fill in the labels
                var labels = oItem.getAnnotationLabels();
                for (var i=0; i < labels.length; i++) {
                    addLabel(oItem, labels[i], labelsForm, but1);
                }
                
                // callback to add comment
                var addComment = function(oItem, commentStr, td, before) {
                    var text = IIDXHelper.createTextArea(null, 2);
                    text.style.width="100%";
                    text.style.boxSizing = "border-box";
                    text.value = commentStr;
                    text.onchange = this.onChangeComment.bind(this, oItem, td);
                    td.insertBefore(text, before);
                    td.insertBefore(document.createTextNode(" "), before);
                }.bind(this);
                
                // add comments button
                var but2 = IIDXHelper.createIconButton("icon-plus");
                but2.onclick = addComment.bind(this, oItem, "", commentsTd, but2);
                commentsTd.appendChild(but2);
                
                // fill in the comments
                var comments = oItem.getAnnotationComments();
                for (var i=0; i < comments.length; i++) {
                    addComment(oItem, comments[i], commentsTd, but2);
                }
                 
                return IIDXHelper.createCollapsibleDiv("Annotations", dom);
            },
            
            /* 
             * clear oItem annotation labels 
             * and replace by all values in textParent child elements
             */
            onChangeLabel(oItem, textParent) {
                var c = textParent.childNodes;
                var strList = [];
                oItem.clearAnnotationLabels();
                for (var i=0; i < c.length; i++) {
                    if (c[i].value) {
                        oItem.addAnnotationLabel(c[i].value); // blanks etc are ignored
                    }
                }
            },
            
            /* 
             * clear oItem annotation labels 
             * and replace by all values in textParent child elements
             */
            onChangeComment(oItem, textParent) {
                var c = textParent.childNodes;
                var strList = [];
                oItem.clearAnnotationComments();
                for (var i=0; i < c.length; i++) {
                    if (c[i].value) {
                        oItem.addAnnotationComment(c[i].value); // blanks etc are ignored
                    }
                }
            },
            
            /**
              * change the class URI (not the namespace)
              */
            onchangeClassName : function(oClass, nameInput) {
                var newURI = oClass.getNamespaceStr() + "#" + nameInput.value;
                var oldURI = oClass.getNameStr();
                
                try {
                    var oc = this.oInfo.editClassName(oClass, newURI);
                    
                } catch (err) {
                    // show error and put text box back to old name
                    ModalIidx.alert("Bad URI", err);
                    nameInput.value = oClass.getNameStr(true);
                    return;
                }
                
                this.oTree.update(this.oInfo, [oldURI], [newURI]);
            },
            
            editClassOLD : function (node) {
                var oClass = this.oInfo.getClass(node.data.value);
                var namespace = oClass.getNamespaceStr();
                var name = oClass.getNameStr(true);
                
                this.legend.innerHTML = "Class " + name;
                
                this.fieldset.innerHTML = "";
                IIDXHelper.fsAddTextInput(this.fieldset, "Namespace:", null, "input-xlarge", namespace, true);
                IIDXHelper.fsAddTextInput(this.fieldset, "Superclass:", null, "input-xlarge", oClass.getParentNameStrs().toString(), true);

                var subtitle = null;
                subtitle = document.createElement("legend");
                subtitle.innerHTML = "Inherited Properties";
                this.fieldset.appendChild(subtitle);
                
                var inheritList = this.oInfo.getInheritedProperties(oClass);
                for (var i=0; i < inheritList.length; i++) {
                    IIDXHelper.fsAddTextInput(this.fieldset, inheritList[i].getName().getFullName(), null, "input-xlarge", inheritList[i].getRange().getFullName(), true);

                }
                
                subtitle = document.createElement("legend");
                subtitle.innerHTML = "Properties";
                this.fieldset.appendChild(subtitle);
                
                var propList = oClass.getProperties();
                for (var i=0; i < propList.length; i++) {
                    IIDXHelper.fsAddTextInput(this.fieldset, propList[i].getName().getFullName(), null, "input-xlarge", propList[i].getRange().getFullName(), true);

                }
                
                subtitle = document.createElement("legend");
                subtitle.innerHTML = "OneOf Restrictions";
                this.fieldset.appendChild(subtitle);
            },
            
            editProperty : function (node) {
                var name = node.data.value;
                this.canvasdiv.innerHTML = "Property<br>" + name;
            },
            
        };
		
		return EditTab;            // return the constructor
	}
	
);
