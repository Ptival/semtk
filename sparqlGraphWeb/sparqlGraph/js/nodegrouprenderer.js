/**
 ** Copyright 2020 General Electric Company
 **
 ** Authors:  Paul Cuddihy
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
 *  ExploreTab - the GUI elements for building an ImportSpec
 *
 *  Basic programmer notes:
 *     ondragover
 *         - preventDefault will allow a drop
 *         - stopPropagation will stop the question from being bubbled up to a parent.
 */

//  Docs
//  https://ww3.arb.ca.gov/ei/tools/lib/vis/docs/network.html

define([	// properly require.config'ed

            'sparqlgraph/js/visjshelper',
            'sparqlgraph/js/modaliidx',
         	'jquery',

            'visjs/vis.min',

			// shimmed
            'sparqlgraph/js/belmont',
            'sparqlgraph/js/cookiemanager'
		],

	function(VisJsHelper, ModalIidx, $, vis) {


		//============ local object  ExploreTab =============
		var NodegroupRenderer = function(canvasdiv) {
            this.ctx = document.createElement("canvas").getContext("2d");

            this.nodegroup = null;
            this.invalidItemTuples = [];    // NodeGroup entryTuple for each item invalid re the model
            this.propEditorCallback = null;
            this.snodeEditorCallback = null;  //done
            this.snodeRemoverCallback = null;
            this.linkBuilderCallback = null;
            this.linkEditorCallback = null;

            this.configdiv = document.createElement("div");
            this.configdiv.style.margin="1ch";
            this.configdiv.id="ngrConfigDiv";
            this.configdiv.style.display="table";
            this.configdiv.style.background = "rgba(32, 16, 16, 0.2)";

            this.canvasdiv = document.createElement("div");
            this.canvasdiv.id="NodegroupRenderer.canvasdiv_" + Math.floor(Math.random() * 10000).toString();
            this.canvasdiv.style.height="100%";
            this.canvasdiv.style.width="100%";

            canvasdiv.appendChild(this.canvasdiv);

            this.network = new vis.Network(this.canvasdiv, {}, NodegroupRenderer.getDefaultOptions(this.configdiv));
            this.network.on('click', this.click.bind(this));

            // data for click(), sorted by Y ascending
            this.nodeCallbackData = {};

            this.edgeCallbackData = {};
        };

        NodegroupRenderer.OPTIONS_COOKIE = "ngrenderOptions";
        NodegroupRenderer.SIZE = 12;
        NodegroupRenderer.VSPACE = 4;
        NodegroupRenderer.STROKE = 2;
        NodegroupRenderer.INDENT = 6;

        NodegroupRenderer.COLOR_NODE = "#f7f8fa";  // light grey
        NodegroupRenderer.COLOR_GRAB_BAR = "#bbbbbb"; //<-tol '#e1e2e5';  // darker grey
        NodegroupRenderer.COLOR_FOREGROUND = 'black';
        NodegroupRenderer.COLOR_CANVAS = 'white';
        NodegroupRenderer.COLOR_RETURNED = "#cc3311"; //<-tol '#cf1e10';     // 'red'
        NodegroupRenderer.COLOR_CONSTRAINED = "#0077bb"; //<-tol '#3b73b9';  // 'blue'
        NodegroupRenderer.COLOR_RET_CONST = "#009988"; //<-tol '#3ca17a';    // 'green'
        NodegroupRenderer.INDENT = 6;
        NodegroupRenderer.COLOR_INVALID_FOREGROUND = 'red';
        NodegroupRenderer.COLOR_INVALID_BACKGROUND = 'pink';

        // https://davidmathlogic.com/colorblind/
        // vibrant
        // Paul Tol  https://personal.sron.nl/~pault/
        NodegroupRenderer.UNION_COLORS = [
            "#0077bb",  // blue   (used last  COLOR_CONSTRAINED)
            "#33bbee",  // cyan     (1st union)
            "#ee7733",  // orange   (2nd union)
            "#ee3377",   // magenta (3rd union)
            "#009988",  // teal     (4th + COLOR_RET_CONST)
            "#cc3311",  // red      (5th + COLOR_RETURNED)
            //"#bbbbbb",   // grey
        ];

        //NodegroupRenderer.UNION_COLORS = ["#332288","#117733","#44AA99","#88CCEE","#DDCC77","#CC6677","#AA4499","#882255"];
        // IBM
        //NodegroupRenderer.UNION_COLORS = ["#648FFF","#785EF0","#DC267F","#FE6100","#FFB000"];
        // wong
        //NodegroupRenderer.UNION_COLORS = [
        //    "#56B4E9",  // sky blue
        //    "#009E73",  // bluish green
        //    "#E69F00",  // orange
        //    "#D55E00",  // burnt red
        //    "#0072B2",  // blue
        //    "#F0E442"   // yellow
        //];


        NodegroupRenderer.getDefaultOptions = function(configdiv) {

            // try to retrieve physics from cookies
            var cookiemgr = new CookieManager(document);
            var optionsStr = cookiemgr.getCookie(NodegroupRenderer.OPTIONS_COOKIE);
            var physics;

            if (optionsStr && optionsStr != "{}") {
                physics = JSON.parse(optionsStr);
            } else {
                physics = {
                    "barnesHut": {
                      "gravitationalConstant": -11500,
                      "centralGravity": 0.1,
                      "springLength": 215,
                      "springConstant": 0.04,
                      "damping": 0.09,
                      "avoidOverlap": 0
                    },
                    "maxVelocity": 50,
                    "minVelocity": 0.75
                };
            }

            return {
                interaction: {
                    navigationButtons: true,
                    keyboard: {
                        bindToWindow: false
                    }
                },
                configure: {
                    enabled: true,
                    container: configdiv,
                    filter: "layout physics",
                    showButton: true
                },
                physics: physics,
                edges: {
                    arrows: {
                        to: {
                            enabled: true,
                            scaleFactor: 0.3
                        }
                    },
                    color: {
                        color: NodegroupRenderer.COLOR_FOREGROUND,
                        hover: 'blue'
                    },

                    font: {
                        color: NodegroupRenderer.COLOR_FOREGROUND,
                        size: 10,
                        face: 'arial',
                        multi: 'html',  // allows <b> in label
                    },
                    width: 1
                }
            };
        };


		NodegroupRenderer.prototype = {

            setPropEditorCallback : function (callback) {
                this.propEditorCallback = callback;
            },
            setSNodeEditorCallback : function (callback) {
                this.snodeEditorCallback = callback;
            },
            setSNodeRemoverCallback : function (callback) {
                this.snodeRemoverCallback = callback;
            },
            setLinkBuilderCallback : function (callback) {
                this.linkBuilderCallback = callback;
            },
            setLinkEditorCallback : function (callback) {
                this.linkEditorCallback = callback;
            },

            click : function(e) {
                if (e.nodes.length > 0) {
                    var n = this.network.body.nodes[e.nodes[0]];
                    var x = e.pointer.canvas.x - n.shape.left;
                    var y = e.pointer.canvas.y - n.shape.top
                    var x_perc = x / n.shape.width;
                    var y_perc = y / n.shape.height;

                    var ndCallbackData = this.nodeCallbackData[n.id];
                    var itemData = ndCallbackData[0];
                    for (var i = 1; i < ndCallbackData.length; i++) {
                        if (y_perc < ndCallbackData[i].y_perc) {
                            break;
                        } else {
                            itemData = ndCallbackData[i];
                        }
                    }
                    var snode = this.nodegroup.getNodeBySparqlID(n.id);
                    if (itemData.type == "SemanticNode") {
                        this.snodeEditorCallback(snode);
                    } else if (itemData.type == "PropertyItem") {
                        this.propEditorCallback(snode.getPropertyByURIRelation(itemData.uri),
                                                n.id);
                    }  else if (itemData.type == "NodeItem") {
                        this.linkBuilderCallback(snode,
                                                snode.getNodeItemByURIConnectBy(itemData.uri));
                    } else if (itemData.type == "header") {
                        if (x_perc > itemData.x_close_perc) {
                            this.snodeRemoverCallback(snode);
                        } else if (x_perc > itemData.x_expand_perc) {
                            // toggle the grabBar (position[0]) expandFlag and redraw
                            this.setExpandFlag(snode, !this.getExpandFlag(snode));
                            this.buildAndUpdateNodeSVG(snode);
                        }
                    }
                } else if (e.edges.length > 0) {
                    var edge = this.network.body.edges[e.edges[0]];
                    var snode = this.nodegroup.getNodeBySparqlID(edge.fromId);
                    var nItem = snode.getNodeItemByURIConnectBy(this.edgeCallbackData[edge.id].uri);
                    var targetSNode = this.nodegroup.getNodeBySparqlID(edge.toId);
                    this.linkEditorCallback(snode, nItem, targetSNode);
                }
            },

            // redraw as if it had been dragged in fresh
            // default behavior is to collapse any unused nodes
            drawCollapsingUnused : function() {
                for (var snode of this.nodegroup.getSNodeList()) {
                    var flag = this.calcExpandNeeded(snode);
                    this.setExpandFlag(snode, flag);
                }
                this.draw(this.nodegroup, []);
            },

            // redraw with all nodes expanded
            drawExpandAll : function() {
                for (var snode of this.nodegroup.getSNodeList()) {
                    this.setExpandFlag(snode, true);
                }

                this.draw(this.nodegroup, []);
            },

            // Update the display to reflect the nodegroup

            draw : function (nodegroup, invalidItemStrings) {

                this.nodegroup = nodegroup;
                this.nodegroup.updateUnionMemberships();  // do this expensive operation once per draw

                this.invalidItemTuples = [];
                for (let itemStr of invalidItemStrings) {
                    this.invalidItemTuples.push(nodegroup.getEntryTuple(itemStr));
                }

                this.drawNodes();
                this.drawEdges();

            },

            drawNodes : function() {
                var nodegroupIDs = this.nodegroup.getSNodeSparqlIDs().slice();
                var graphIDs = this.network.body.data.nodes.getIds();

                // changed nodes
                var changedIDs = [];
                for (var id of graphIDs) {
                    if (nodegroupIDs.indexOf(id) > -1) {
                        var snode = this.nodegroup.getNodeBySparqlID(id);
                        this.buildAndUpdateNodeSVG(snode);
                        changedIDs.push(id);
                    }
                }

                // new nodes
                var newIDs = [];
                for (var id of nodegroupIDs) {
                    if (graphIDs.indexOf(id) == -1) {
                        var snode = this.nodegroup.getNodeBySparqlID(id);
                        this.buildAndUpdateNodeSVG(snode);
                        newIDs.push(id);
                    }
                }

                // deleted
                var deletedIDs = [];
                for (var id of graphIDs) {
                    if (nodegroupIDs.indexOf(id) == -1) {
                        deletedIDs.push(id);
                        delete this.nodeCallbackData["id"]
                    }
                }
                this.network.body.data.nodes.remove(deletedIDs);
            },

            buildEdgeLabel : function(nItem, snode2) {
                var UNION_SYMBOL = "\u222A ";
                var label = "";
                var unionKey = this.nodegroup.getUnionKey(this.nodegroup.getNodeItemParentSNode(nItem), nItem, snode2);
                var optMinus = nItem.getOptionalMinus(snode2);
                var reverseFlag = (unionKey < 0 || optMinus == NodeItem.OPTIONAL_REVERSE || optMinus == NodeItem.MINUS_REVERSE)

                if (reverseFlag) {
                    label += "} ";
                }
                if (unionKey != null) {
                    label += UNION_SYMBOL + " ";
                }

                if (optMinus == NodeItem.OPTIONAL_TRUE || optMinus == NodeItem.OPTIONAL_REVERSE) {
                    label += "optional ";
                }
                if (optMinus == NodeItem.MINUS_TRUE || optMinus == NodeItem.MINUS_REVERSE) {
                    label += "minus ";
                }

                label += nItem.getKeyName() + nItem.getQualifier(snode2);

                if (false && !reverseFlag) {
                    label += " {";
                }
                return label;
            },

            drawEdges : function() {
                // edges: update all since it is cheap

                var edgeIDsToRemove = this.network.body.data.edges.getIds();
                var edgeData = [];
                for (var snode of this.nodegroup.getSNodeList()) {
                    for (var nItem of snode.getNodeList()) {
                        for (var snode2 of nItem.getSNodes()) {
                            var fromID = snode.getSparqlID();
                            var toID = snode2.getSparqlID();
                            var id = fromID + "-" + toID;
                            var label = this.buildEdgeLabel(nItem, snode2);

                            var edgeFont = {color: NodegroupRenderer.COLOR_FOREGROUND, background: NodegroupRenderer.COLOR_CANVAS};

                            // color invalid
                            if (this.edgeIsInvalid(nItem, snode2)) {
                                edgeFont = {color: NodegroupRenderer.COLOR_INVALID_FOREGROUND, background: NodegroupRenderer.COLOR_INVALID_BACKGROUND};
                            }

                            //var edgeFont;
                            //if (nItem.getOptionalMinus(snode2) != NodeItem.OPTIONAL_FALSE) {
                            //    edgeFont = {color: 'red', background: 'lightgray'};
                            //} else  {
                            //    edgeFont = {color: NodegroupRenderer.COLOR_FOREGROUND, background: NodegroupRenderer.COLOR_CANVAS};
                            //}
                            var unionMemberColor = this.getUnionMembershipColor(snode, nItem, snode2) || NodegroupRenderer.COLOR_FOREGROUND;
                            var unionColor = this.getUnionColor(nItem, snode2);
                            if (unionColor != null) {
                                edgeFont.color = unionColor;
                            }
                            var edge = {
                                id:     id,
                                from:   fromID,
                                to:     toID,
                                label:  label,
                                color:  {color: unionMemberColor, highlight: unionMemberColor, inherit: false},
                                font :  edgeFont
                            };
                            edgeData.push(edge);
                            this.edgeCallbackData[id] = {uri: nItem.getItemUri()};

                            // remove this edge from edgeIDsToRemove
                            var removeIndex = edgeIDsToRemove.indexOf(id);
                            if (removeIndex > -1) {
                                edgeIDsToRemove.splice(removeIndex, 1);
                            }
                        }
                    }
                }
                // update all the new edge data
                this.network.body.data.edges.update(edgeData);

                // remove any edges no longer in the nodegroup
                this.network.body.data.edges.remove(edgeIDsToRemove);
                for (var id of edgeIDsToRemove) {
                    delete this.edgeCallbackData[id];
                }
            },

            // get deepest union item belongs to, or null
            getUnionMembershipColor : function (snode, optItem, optTarget) {
                var union = this.nodegroup.getUnionMembership(snode, optItem, optTarget);
                if (union != null) {
                    return  NodegroupRenderer.UNION_COLORS[union % NodegroupRenderer.UNION_COLORS.length];
                } else {
                    return null;
                }
            },

            // get union the item helps define, or null
            getUnionColor : function (item, optTarget) {
                var union;
                if (item instanceof NodeItem) {
                    union = this.nodegroup.getUnionKey(this.nodegroup.getNodeItemParentSNode(item), item, optTarget);
                    if (union != null) {
                        union = Math.abs(union);
                    }
                } else if (item instanceof PropertyItem) {
                    union = this.nodegroup.getUnionKey(this.nodegroup.getPropertyItemParentSNode(item), item);

                } else {      // snode
                    union = this.nodegroup.getUnionKey(item);
                }

                if (union != null) {
                    return  NodegroupRenderer.UNION_COLORS[union % NodegroupRenderer.UNION_COLORS.length];
                } else {
                    return null;
                }
            },

            nodeIsInvalid : function(snode) {
                for (let t of this.invalidItemTuples) {
                    if (t.length == 1 && t[0] == snode)
                        return true;
                }
                return false;
            },

            propIsInvalid : function(nodeOrPropItem) {
                for (let t of this.invalidItemTuples) {
                    if (t.length > 1 && t[1] == nodeOrPropItem) {
                        // node item is only invalid if it came back with null target
                        // otherwise only some edges are invalid
                        if (nodeOrPropItem instanceof PropertyItem || (t.length > 2 && t[2] == null))  {
                            return true;
                        }
                    }
                }
                return false;
            },

            edgeIsInvalid : function(nodeItem, target ) {
                for (let t of this.invalidItemTuples) {
                    if (t.length > 2 && t[1] == nodeItem && t[2] == target)
                        return true;
                }
                return false;
            },

            //
            // Change an snode to a network node and call nodes.update
            // (adding or replacing the existing node)
            //
            // Also pushes info to this.callbackData, which is used for callbacks
            //
            buildAndUpdateNodeSVG : function(snode) {
                var y = 0;
                var x = 0;
                var hwd;
                var maxWidth = 0;
                var svg = document.createElement("svg");
                var myCallbackData = [];
                var expandFlag = this.getExpandFlag(snode);

                //  build support vector graphic with a default size
                svg.setAttribute('xmlns', "http://www.w3.org/2000/svg");
                svg.setAttribute('width', 200);
                svg.setAttribute('height', 60);

                var foreground = NodegroupRenderer.COLOR_FOREGROUND;

                // fill with a rectangle
                var rect = document.createElement("rect");
                rect.setAttribute('x', "0");
                rect.setAttribute('y', "0");
                rect.setAttribute('width', "100%");
                rect.setAttribute('height', "100%");
                rect.setAttribute('fill', NodegroupRenderer.COLOR_NODE);
                rect.setAttribute('stroke-width', NodegroupRenderer.STROKE);
                rect.setAttribute('stroke', foreground);
                svg.appendChild(rect);
                y += NodegroupRenderer.STROKE;

                // skip enough room for the grab bar at the end
                y += this.getGrabBarHeight();
                y += NodegroupRenderer.VSPACE;

                // add the sparqlID
                hwd = this.addSparqlID(svg, snode, y);
                y += hwd.height;
                maxWidth = hwd.width > maxWidth ? hwd.width : maxWidth;
                myCallbackData.push(hwd.data);

                if (expandFlag) {
                    // add property items
                    for (var p of snode.getPropList()) {
                        hwd = this.addProperty(svg, p, y);
                        y += hwd.height;
                        maxWidth = hwd.width > maxWidth ? hwd.width : maxWidth;
                        myCallbackData.push(hwd.data);
                    }
                    // add node items
                    for (var n of snode.getNodeList()) {
                        hwd = this.addProperty(svg, n, y);
                        y += hwd.height;
                        maxWidth = hwd.width > maxWidth ? hwd.width : maxWidth;
                        myCallbackData.push(hwd.data);
                    }
                }

                // add a little padding on height and width
                x = maxWidth + NodegroupRenderer.INDENT * 5;
                y += NodegroupRenderer.VSPACE * 2;

                // set height and width of svg
                svg.setAttribute("height", y);
                svg.setAttribute("width", x)

                // now that width is set, add the grab bar (with it's right-justified stuff)
                var callbackData = this.addGrabBar(svg, snode, expandFlag)
                myCallbackData.unshift(callbackData);   // sneak it on the beginning of the list since it it supposed to be sorted by Y ascending

                // add y_perc to all callback data: percentage of y at bottom of item
                for (var i of myCallbackData) {
                    i.y_perc = i.y / y;
                }

                // change grab bar special cases to percent
                myCallbackData[0].x_close_perc = myCallbackData[0].x_close / x;
                myCallbackData[0].x_expand_perc = myCallbackData[0].x_expand / x;

                this.nodeCallbackData[snode.getSparqlID()] = myCallbackData;

                // build the svg image
                var im = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg.outerHTML);

                // take a swag at node size based on height
                var visjsNodeSize = y / 4;

                this.network.body.data.nodes.update([{id: snode.getSparqlID(), image: im, shape: "image", size: visjsNodeSize }]);
            },

            calcExpandNeeded : function(snode) {
                return (snode.getReturnedPropertyItems().length + snode.getConstrainedPropertyItems().length) > 0;
            },

            // figure out if node is expanded
            getExpandFlag : function(snode) {

                // if node has been drawn before, retrieve
                if (this.nodeCallbackData.hasOwnProperty(snode.getSparqlID())) {
                    expandFlag = this.nodeCallbackData[snode.getSparqlID()][0].expandFlag;
                } else {
                    // first time draw: always expand
                    expandFlag = true;
                }
                return expandFlag;
            },

            // change the expandFlag of an already-drawn node
            setExpandFlag : function(snode, val) {
                this.nodeCallbackData[snode.getSparqlID()][0].expandFlag = val;
            },

            deleteExpandFlag : function(snode) {
                delete this.nodeCallbackData[snode.getSparqlID()][0].expandFlag;
            },

            // In order to allow items to be right-justified, grab bar must be added last
            // So there are separate calls
            //  1.  get the height to skip at the beginning of the node-building
            //  2.  add the bar at the end (in the empty skipped space)-- after width is set
            getGrabBarHeight : function (svg) {
                return Math.floor(1.5 * NodegroupRenderer.SIZE);
            },

            addGrabBar : function(svg, snode, expandFlag) {

                var height = this.getGrabBarHeight();
                var fill = this.getUnionMembershipColor(snode) || NodegroupRenderer.COLOR_GRAB_BAR;

                // draw the basic rectangle
                var rect = document.createElement("rect");
                rect.setAttribute('x', "0");
                rect.setAttribute('y', "0");
                rect.setAttribute('width', "100%");
                rect.setAttribute('height', height);
                rect.setAttribute('fill', fill);
                rect.setAttribute('stroke-width', NodegroupRenderer.STROKE);
                rect.setAttribute('stroke', NodegroupRenderer.COLOR_FOREGROUND);
                svg.appendChild(rect);

                var width = svg.getAttribute("width");
                var callbackData = {};

                // draw X
                var elementTop = height * 0.2;
                var elementSize = height * 0.6;
                var elementBot = elementTop + elementSize;
                var x = width - elementSize * 2;
                this.drawX(svg, x, elementTop, x+elementSize, elementTop+ elementSize, 1,NodegroupRenderer.COLOR_FOREGROUND);
                callbackData.x_close = x ;


                // draw expand / collapse
                x = width - elementSize * 4;
                if (expandFlag) {
                    var y = elementTop + elementSize * .75;
                    this.drawLine(svg, x, y , x+elementSize, y, 1, NodegroupRenderer.COLOR_FOREGROUND);
                } else {
                    var y = elementTop;
                    this.drawBox(svg, x, y, x+elementSize, y+elementSize, NodegroupRenderer.COLOR_GRAB_BAR, NodegroupRenderer.COLOR_FOREGROUND);
                }
                callbackData.x_expand = x;

                // draw mover
                x = NodegroupRenderer.INDENT;
                elementTop = height * 0.2;
                elementSize = height * 1;
                elementBot = elementTop + height * .6 ;
                for (var yy=elementTop; yy <= elementBot; yy += (elementBot - elementTop) / 3) {
                    this.drawLine(svg, x, yy , x+elementSize, yy, 1,NodegroupRenderer.COLOR_FOREGROUND);
                }

                callbackData.y = height;
                callbackData.type = "header";
                callbackData.value="";
                callbackData.expandFlag = expandFlag;

                return(callbackData);
            },

            // add SparqlID line
            // generate a [height, width, data]
            addSparqlID : function(svg, snode, y) {

                var foreground = NodegroupRenderer.COLOR_FOREGROUND;

                var bot = y + NodegroupRenderer.VSPACE + NodegroupRenderer.SIZE;
                var x = NodegroupRenderer.INDENT;
                var size = NodegroupRenderer.SIZE * 1.5;

                var checked = false;
                var x1 = x;
                var y1 = y;

                // add the union symbol
                var unionColor = this.getUnionColor(snode);
                if (unionColor != null) {
                    var text = document.createElement('text');
                    text.setAttribute('x', x);
                    text.setAttribute('y', bot);
                    text.setAttribute('font-size', size + "px");
                    text.setAttribute('font-family', "Arial");
                    text.setAttribute('font-weight', "bold");
                    text.setAttribute('fill', unionColor);
                    text.innerHTML = "&cup;";
                    svg.appendChild(text);
                }
                x += (size);

                if (snode.hasAnyReturn()) {
                    checked = true;
                    if (snode.hasConstraints()) {
                        foreground = NodegroupRenderer.COLOR_RET_CONST;
                    } else {
                        foreground = NodegroupRenderer.COLOR_RETURNED;
                    }
                } else if (snode.hasConstraints()) {
                    checked = true;
                    foreground = NodegroupRenderer.COLOR_CONSTRAINED;
                }
                this.drawCheckBox(svg, x, bot, size, checked, foreground );

                var text = document.createElement('text');
                text.setAttribute('x', x + size + NodegroupRenderer.INDENT);
                text.setAttribute('y', bot);
                text.setAttribute('font-size', size + "px");
                text.setAttribute('font-family', "Arial");
                text.setAttribute('fill', foreground);

                var uri = snode.getURI(true);
                var binding = snode.getBindingOrSparqlID();
                text.innerHTML = uri + ((binding != "?"+uri) ? (" - " + snode.getBindingOrSparqlID()) : "");

                var height = NodegroupRenderer.VSPACE + size;
                var width = x + size + NodegroupRenderer.INDENT + this.measureTextWidth(text);
                var callbackData = { y: y, type: snode.getItemType(), value: text.innerHTML };

                // draw invalid box
                if (this.nodeIsInvalid(snode)) {
                    this.drawBox(svg, x1, y1, x1+width, y1+height,NodegroupRenderer.COLOR_INVALID_BACKGROUND, NodegroupRenderer.COLOR_INVALID_FOREGROUND);
                }
                svg.appendChild(text);

                return({"height":height, "width":width, "data":callbackData});
            },

            // add property line
            // generate a [height, width, data]
            addProperty : function(svg, item, y, data) {
                var bot = y + NodegroupRenderer.VSPACE + NodegroupRenderer.SIZE;
                var x = NodegroupRenderer.INDENT;
                var size = NodegroupRenderer.SIZE;
                var x1 = x;
                var y1 = y + NodegroupRenderer.VSPACE;
                // add the union symbol to propertyItems where needed
                if (item instanceof PropertyItem) {
                    var unionColor = this.getUnionColor(item);
                    if (unionColor != null) {
                        var text = document.createElement('text');
                        text.setAttribute('x', x);
                        text.setAttribute('y', bot);
                        text.setAttribute('font-size', size + "px");
                        text.setAttribute('font-family', "Arial");
                        text.setAttribute('font-weight', "bold");
                        text.setAttribute('fill', unionColor);
                        text.innerHTML = "&cup;";
                        svg.appendChild(text);
                    }
                }
                x += (size);

                var checked = false;
                var foreground = NodegroupRenderer.COLOR_FOREGROUND;
                if (item instanceof PropertyItem) {
                    if (item.hasAnyReturn() ) {
                        checked = true;
                        if (item.hasConstraints()) {
                            foreground = NodegroupRenderer.COLOR_RET_CONST;
                        } else {
                            foreground = NodegroupRenderer.COLOR_RETURNED;
                        }
                    } else if (item.hasConstraints()) {
                        checked = true;
                        foreground = NodegroupRenderer.COLOR_CONSTRAINED;
                    }
                }
                this.drawCheckBox(svg, x, bot, size, checked, foreground);
                x += (size + NodegroupRenderer.INDENT);

                var text = document.createElement('text');
                text.setAttribute('x', x);
                text.setAttribute('y', bot);
                text.setAttribute('font-size', size + "px");
                text.setAttribute('font-family', "Arial");
                text.setAttribute('fill', foreground);

                text.innerHTML = item.getKeyName() + " : " + item.getValueType();
                if (! (item instanceof NodeItem)) {
                    var retName = item.getBindingOrSparqlID();
                    if (retName != "") {
                        text.innerHTML += " - " + retName;
                    }
                }


                var height = NodegroupRenderer.VSPACE + size;
                var width = x + this.measureTextWidth(text);
                var callbackData = { y: y, type: item.getItemType(), value: item.getKeyName(), uri: item.getItemUri() };

                // draw invalid box
                if (this.propIsInvalid(item)) {
                    this.drawBox(svg, x1, y1, x1+width, y1+height, NodegroupRenderer.COLOR_INVALID_BACKGROUND, NodegroupRenderer.COLOR_INVALID_FOREGROUND);
                }

                svg.appendChild(text);

                return({"height":height, "width":width, "data":callbackData});
            },

            // Add a checkbox.
            // Black when unchecked, Red when checked
            // x,y is bottom left to match text
            drawCheckBox : function(svg, x, y, size, checked, foreground) {
                // margin twice as big on top and right
                var m = Math.floor(size/7);
                var s = size - m - m - m;
                var top = y - size + m + m;
                var left = x + m;

                this.drawBox(svg, left, top, left + s, top + s, NodegroupRenderer.COLOR_NODE, foreground)

                if (checked) {
                    var x1 = left;
                    var x2 = left + s;
                    var y1 = top;
                    var y2 = top + s;
                    this.drawX(svg, x1, y1, x2, y2, 1, foreground);
                }
            },

            // add a line
            drawBox : function(svg, x1, y1, x2, y2, fillColor, strokeColor) {
                var rect = document.createElement('rect');
                rect.setAttribute('x', x1);
                rect.setAttribute('y', y1);
                rect.setAttribute('width', (x2 - x1) + "px");
                rect.setAttribute('height', (y2 - y1) + "px");
                rect.setAttribute('fill', fillColor);
                rect.setAttribute('stroke', strokeColor);
                svg.appendChild(rect);
            },

            // add a line
            drawLine : function(svg, x1, y1, x2, y2, strokeWidth, strokeColor) {
                var line = document.createElement("line");
                line.setAttribute('x1',x1);
                line.setAttribute('y1', y1);
                line.setAttribute('x2',x2);
                line.setAttribute('y2', y2);
                line.setAttribute('stroke-width', strokeWidth);
                line.setAttribute('stroke', strokeColor);
                svg.appendChild(line);
            },

            drawX : function (svg, x1, y1, x2, y2, strokeWidth, strokeColor) {
                this.drawLine(svg, x1, y1, x2, y2, strokeWidth,strokeColor);
                this.drawLine(svg, x1, y2, x2, y1, strokeWidth,strokeColor);
            },

            measureTextWidth : function (textElem) {
                var f = textElem.getAttribute("font-size") + " " + textElem.getAttribute("font-family");
                this.ctx.font = f;
                var ctx_ret =  this.ctx.measureText(textElem.innerHTML).width;
                //console.log("WIDTH " + f + ":" + textElem.innerHTML + "= " + ctx_ret);
                return ctx_ret;
            },

            showConfigDialog : function() {

                // hack at getting the UI colors so they don't look terrible
                for (var e of this.configdiv.children) {
                    e.style.backgroundColor='white';
                    for (var ee of e.children) {
                        if (! ee.innerHTML.startsWith("generate")) {
                            ee.style.backgroundColor='white';
                        }
                    }
                }

                var m = new ModalIidx("ModalIidxAlert");
                m.showOK("Network physics", this.configdiv, this.saveConfigToCookie.bind(this));
            },

            saveConfigToCookie : function() {
                var options = this.network.getOptionsFromConfigurator();

                var cookiemgr = new CookieManager(document);
                cookiemgr.setCookie(NodegroupRenderer.OPTIONS_COOKIE, JSON.stringify(options.physics));

            }


        }

		return NodegroupRenderer;            // return the constructor
	}

);
