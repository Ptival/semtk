/*
 *  Dracula Graph Layout and Drawing Framework 0.0.3alpha
 *  (c) 2010 Philipp Strathausen <strathausen@gmail.com>, http://strathausen.eu
 *  Contributions by Jake Stothard <stothardj@gmail.com>.
 *
 *  based on the Graph JavaScript framework, version 0.0.1
 *  (c) 2006 Aslak Hellesoy <aslak.hellesoy@gmail.com>
 *  (c) 2006 Dave Hoover <dave.hoover@gmail.com>
 *
 *  Ported from Graph::Layouter::Spring in
 *    http://search.cpan.org/~pasky/Graph-Layderer-0.02/
 *  The algorithm is based on a spring-style layouter of a Java-based social
 *  network tracker PieSpy written by Paul Mutton <paul@jibble.org>.
 *
 *  This code is freely distributable under the MIT license. Commercial use is
 *  hereby granted without any cost or restriction.
 *
 *  Links:
 *
 *  Graph Dracula JavaScript Framework:
 *      http://graphdracula.net
 *
 /*--------------------------------------------------------------------------*/

/* 
	Additional code added Justin McHugh and Paul Cuddihy 
	for SparqlGraph.
	
	Backward compat has been largely maintained. 
*/

/*
 * Edge Factory
 */
var AbstractEdge = function() {
}
AbstractEdge.prototype = {
    hide: function() {
        this.connection.fg.hide();
        this.connection.bg && this.bg.connection.hide();
        this.connection.label.hide();
    }
};
var EdgeFactory = function() {
    this.template = new AbstractEdge();
    this.template.style = new Object();
    this.template.style.directed = false;
    this.template.weight = 1;
};
EdgeFactory.prototype = {
    build: function(source, target) {
        var e = jQuery.extend(true, {}, this.template);
        e.source = source;
        e.target = target;
        return e;
    }
};

/*
 * Graph
 */
var Graph = function() {
    this.nodes = {};
    this.edges = [];
    this.snapshots = []; // previous graph states TODO to be implemented
    this.edgeFactory = new EdgeFactory();
};
Graph.prototype = {
    /* 
     * add a node
     * @id          the node's ID (string or number)
     * @content     (optional, dictionary) can contain any information that is
     *              being interpreted by the layout algorithm or the graph
     *              representation
     */
    addNode: function(snode, content) {
        /* testing if node is already existing in the graph */
        
        if(this.nodes[snode.getSparqlID() ] == undefined) {
            this.nodes[snode.getSparqlID() ] = snode.node;   //new Graph.Node(snode.NodeName, snode.getSparqlID(), snode.node);
            //console.log("trying to create a node" + snode.NodeName + " " + snode.getSparqlID());
        }
        else{
          //console.log("no need to create a node" + snode.NodeName + " " + snode.getSparqlID());	
        
        }
        return this.nodes[snode.getSparqlID()];
    },
    // added by justin for belmont
    addExistingNode: function(existingSnode) {
    	var id = existingSnode.id;
    	
    	this.nodes[id] = existingSnode;
    	//console.log("adding the existing node " + id );
    	
    },
    
    // end alucard additions
    addEdge: function(source, target, style) {
        var s = this.addNode(source);
        var t = this.addNode(target);
        //console.log("now trying to add edge: " + source.getSparqlID() + " ---> " + target.getSparqlID());
        
        // return if it already exists
        for (var i=0; i < this.edges.length; i++) {
        	if (this.edges[i].source == s && this.edges[i].target == t && this.edges[i].style.label == style.label) {
        		return;
        	}
        }
        
        // otherwise add it
        var edge = this.edgeFactory.build(s, t);
        jQuery.extend(edge.style,style);
        s.edges.push(edge);
        this.edges.push(edge);
        // NOTE: Even directed edges are added to both nodes.
        t.edges.push(edge);
    },
    
    /* TODO to be implemented
     * Preserve a copy of the graph state (nodes, positions, ...)
     * @comment     a comment describing the state
     */
    snapShot: function(comment) {
        /* FIXME
        var graph = new Graph();
        graph.nodes = jQuery.extend(true, {}, this.nodes);
        graph.edges = jQuery.extend(true, {}, this.edges);
        this.snapshots.push({comment: comment, graph: graph});
        */
    },
    
    removeEdge: function(source, target) {
    	// remove edges between source and target
    	for(var i = 0; i < this.edges.length; i++) {
    		if (this.edges[i].source == source && this.edges[i].target == target) {
    			this.edges[i].hide();
    			this.edges.splice(i, 1);
    			i--;
    		}
    	}
    },
    
    removeNode: function(id) {
    	// remove all edges to and from
    	for(var i = 0; i < this.edges.length; i++) {
    		if (this.edges[i].source.id == id || this.edges[i].target.id == id) {
    			this.edges.splice(i, 1);
    			i--;
    		}
    	}
    	
    	// delete the node ('delete' instead of 'splice' since it is an assoc array)
    	this.nodes[id].hide();
    	delete this.nodes[id];	
    },

};

/*
 * Node
 */
Graph.Node = function(id, SparqlID, node_in){
    node = node_in || {};
    node.id = SparqlID;
    node.parentclass = id;
    node.edges = [];
    node.propLabels = [];
    node.nodeLabels = [];
    node.subnodes = [];
    node.parentSNode = '';
    node.collapsed = false;       // is node collapsed
    node.collapsedItems = [];     // items removed when collapsed (so edges draw properly)
    node.collapsedItem0Box = {};  // former location of item[0]

    
    node.hide = function() {
        this.hidden = true;
        this.shape && this.shape.hide(); /* FIXME this is representation specific code and should be elsewhere */
        //for(i in this.edges)
        for (var i = 0; i < this.edges.length; i++ )
        {
        	//var testID = "?" + id;
            if(this.edges[i].source == this || this.edges[i].target == this){
				this.edges[i].connection.label.hide();
            	this.edges[i].hide;
            	this.edges[i].hide();
 			    }
        }
    };
    node.show = function() {
        this.hidden = false;
        this.shape && this.shape.show();
        for(i in this.edges)
            (this.edges[i].source.id == id || this.edges[i].target == id) && this.edges[i].show && this.edges[i].show();
    };
 	node.setPropLabels = function(arrPropNames){
		var arrSize = arrPropNames.length;
		for(var i = 0; i < arrSize; i++){
			// add each in turn.
			this.propLabels.push(arrPropNames[i].getKeyName() + " : " + arrPropNames[i].getValueType());
		}
 	};
	node.setNodeLabels = function(arrNodeNames){
		var arrSize = arrNodeNames.length;
		for(var i = 0; i < arrSize; i++){
			// add each in turn.
			this.nodeLabels.push(arrNodeNames[i].getKeyName() + " : " + arrNodeNames[i].getValueType());
		}		
	};
	node.setParent = function (parentSNode){
		this.parentSNode = parentSNode;
	};
    
    return node;
};
Graph.Node.prototype = {
	// added by justin, for reasons...
		 
};

/*
 * Renderer base class
 */
Graph.Renderer = {};

/*
 * Renderer implementation using RaphaelJS
 */
Graph.Renderer.Raphael = function(element, graph, width, height) {
    this.width = width || 400;
    this.height = height || 400;
    var selfRef = this;
    this.r = Raphael(element, this.width, this.height);
    this.radius = 100; /* max dimension of a node */
    this.graph = graph;
    this.mouse_in = false;
    
    /* TODO default node rendering function */
    if(!this.graph.render) {
        this.graph.render = function() {
            return;
        }
    }
    
    /*
     * Dragging
     */
    this.isDrag = false;
    this.dragger = function (e) {
        this.dx = e.clientX;
        this.dy = e.clientY;
        selfRef.isDrag = this;
        this.set && this.set.animate({"fill-opacity": .1}, 200) && this.set.toFront();
        e.preventDefault && e.preventDefault();
    };
    
    var d = document.getElementById(element);
    d.onmousemove = function (e) {
        e = e || window.event;
        if (selfRef.isDrag) {
            var bBox = selfRef.isDrag.set.getBBox();
            // TODO round the coordinates here (eg. for proper image representation)
            var newX = e.clientX - selfRef.isDrag.dx + (bBox.x + bBox.width / 2);
            var newY = e.clientY - selfRef.isDrag.dy + (bBox.y + bBox.height / 2);
            /* prevent shapes from being dragged out of the canvas */
            var clientX = e.clientX - (newX < 20 ? newX - 20 : newX > selfRef.width - 20 ? newX - selfRef.width + 20 : 0);
            var clientY = e.clientY - (newY < 20 ? newY - 20 : newY > selfRef.height - 20 ? newY - selfRef.height + 20 : 0);
            
            selfRef.isDrag.set.translate(clientX - Math.round(selfRef.isDrag.dx), clientY - Math.round(selfRef.isDrag.dy));
            
            //            console.log(clientX - Math.round(selfRef.isDrag.dx), clientY - Math.round(selfRef.isDrag.dy));
            for (var i in selfRef.graph.edges) {
                selfRef.graph.edges[i].connection && selfRef.graph.edges[i].connection.draw();
            }
            //selfRef.r.safari();
            selfRef.isDrag.dx = clientX;
            selfRef.isDrag.dy = clientY;
        }
    };
    d.onmouseup = function () {
        selfRef.isDrag && selfRef.isDrag.set.animate({"fill-opacity": .6}, 500);
        selfRef.isDrag = false;
    };
    this.draw();
};

Graph.Renderer.Raphael.HEADER_SIZE = 3;   // number of non property sub-nodes
Graph.Renderer.Raphael.VMARGIN = 2;
Graph.Renderer.Raphael.HMARGIN = 10;
Graph.Renderer.Raphael.HTAB = 5;
Graph.Renderer.Raphael.RESTORE_TXT = "\u2B1C";
Graph.Renderer.Raphael.COLLAPSE_TXT = "\u2581";
Graph.Renderer.Raphael.DELETE_TXT = "\u2613";

Graph.Renderer.Raphael.prototype = {
    translate: function(point) {
        return [
            (point[0] - this.graph.layoutMinX) * this.factorX + this.radius,
            (point[1] - this.graph.layoutMinY) * this.factorY + this.radius
        ];
    },

    rotate: function(point, length, angle) {
        var dx = length * Math.cos(angle);
        var dy = length * Math.sin(angle);
        return [point[0]+dx, point[1]+dy];
    },

    collapseNode : function(node) {
        if (node.collapsed) { return; }
        node.collapsed = true;
        
        var numLabels = node.propLabels.length + node.nodeLabels.length;
                                          
        // Collapse a node

        // hide sub-nodes and shrink the box height
        for (var i=node.subnodes.length-1; i > node.subnodes.length -1 - numLabels; i--) {
            // hide, and shrink the main parent height
            node.subnodes[i].hide();  
            node.shape[0].attr({"height" : node.shape[0].attrs.height - node.subnodes[i].getBBox().height - Graph.Renderer.Raphael.VMARGIN});
        }

        // stash the collapsed items
        node.collapsedItems = node.shape.items.slice(Graph.Renderer.Raphael.HEADER_SIZE+1,);
        node.shape.items = node.shape.items.slice(0,Graph.Renderer.Raphael.HEADER_SIZE+1);
        node.collapsedItem0Box = node.shape.items[0].getBBox();

        // change the button character
        node.subnodes[1].attr({"text" : Graph.Renderer.Raphael.RESTORE_TXT});
        
        // redraw connections
        for (var i=0; i < node.edges.length; i++) {
            node.edges[i].connection.draw();
        }
    },
    
    uncollapseNode: function(node) {
        if (!node.collapsed) { return; }
        node.collapsed = false;
        
        var numLabels = node.propLabels.length + node.nodeLabels.length;
        
        // Un-collapse a node

        // calculate offset in case node has moved
        var item0Box = node.shape.items[0].getBBox();
        var xOffset = item0Box.x - node.collapsedItem0Box.x;
        var yOffset = item0Box.y - node.collapsedItem0Box.y;

        // move all the stashed items
        for (var j=0; j < node.collapsedItems.length; j++) {
            var jItem = node.collapsedItems[j];
            jItem.attr({"x": jItem.attrs.x + xOffset});
            jItem.attr({"y":  jItem.attrs.y + yOffset});
        }

        // restore stashed items and expand the box
        node.shape.items = node.shape.items.concat(node.collapsedItems);
        node.collapsedItems = [];
        for (var i=node.subnodes.length-1; i > node.subnodes.length -1 - numLabels; i--) {  
            node.subnodes[i].show().toFront();
            node.shape[0].attr({"height" : node.shape[0].attrs.height + node.subnodes[i].getBBox().height + Graph.Renderer.Raphael.VMARGIN});
        }

        // change the button character
        node.subnodes[1].attr({"text" : Graph.Renderer.Raphael.COLLAPSE_TXT});
        
        // redraw connections
        for (var i=0; i < node.edges.length; i++) {
            node.edges[i].connection.draw();
        }
    },
    
    draw: function() {
        this.factorX = (this.width - 2 * this.radius) / (this.graph.layoutMaxX - this.graph.layoutMinX);
        this.factorY = (this.height - 2 * this.radius) / (this.graph.layoutMaxY - this.graph.layoutMinY);
        
        var keys = Object.keys(this.graph.nodes);            // PEC changed loop due to bug in original (missing "var") and unexplainable javascript behavior
        for (var k=0; k < keys.length; k++) {
            this.drawNode(this.graph.nodes[keys[k]]);
        }
        for (var i = 0; i < this.graph.edges.length; i++) {
            this.drawEdge(this.graph.edges[i]);
        }
    },

    drawNode: function(node) {
        var point = this.translate([node.layoutPosX, node.layoutPosY]);
        node.point = point;

        /* if node has already been drawn, move the nodes */
        if(node.shape) {
            var oBBox = node.shape.getBBox();
            var opoint = { x: oBBox.x + oBBox.width / 2, y: oBBox.y + oBBox.height / 2};
            node.shape.translate(Math.round(point[0] - opoint.x), Math.round(point[1] - opoint.y));
            this.r.safari();
            return node;
        }/* else, draw new nodes */

        var shape;
        var graph = this.graph;
        /* if a node renderer function is provided by the user, then use it 
           or the default render function instead */
        if(!node.render) {
            node.render = function(r, node) {
                /* the default node drawing */
            	
            	var hue = "#0055FF";
                var color = Raphael.getColor();
                var fillVal = "90-#A9C6FF:5-#EEF4FF:100";   // blue gradient
                fillVal = "#E7E7EB";
                var rect = r.rect(0, 0, 50, 80).attr({fill: fillVal, stroke: "#020200", "stroke-width": 2});
                rect.attr({"cursor": "move"});
                /* set DOM node ID */
                rect.node.id = node.label || node.id;
                
                
                if(node.propLabels.length == -1 ){
	                var label = r.text(5,5,node.label || node.id);
	                label.attr({"font-size": 16, "font-family": "Arial, Helvetica, sans-serif"});
	                label.attr({"x" : getLineX(label.getBBox().width, Graph.Renderer.Raphael.HMARGIN)});
	                label.attr({"y" : getLineY(0, Graph.Renderer.Raphael.VMARGIN, [label])});
	                label.node.ondblclick = function () { alert(node.label || node.id);};
	                
	                shape = r.set().
	                push(rect).
	                push(label);
             
                }
                else{
                	
                	// loop over the props
                	var labelN = [];
                	var psize = node.propLabels.length;
                	
                	// ==== "X" for deleting SNodes
                	var deleteX = r.text(5,5, Graph.Renderer.Raphael.DELETE_TXT);
                	deleteX.attr({"font-size": 14, "font-family": "Arial, Helvetica, sans-serif", "fill" : "black", "stroke-width": 3, "font-weight": "bold"});
                	
                	// delete callbacks
                	deleteX.node.onmousedown = function (e) {
            			registerMouseDown(e);
            		};
            		
            		deleteX.node.onmouseup = function (pnode, graph, e) {
            			registerMouseUp(e);
            			if (mouseUpIsClick()) {
                            pnode.parentSNode.callAsyncSNodeRemover(); 
            			}
            		}.bind("fake_this", node, graph);
                	
                    labelN.push(deleteX);
        			deleteX.attr({"y": deleteX.getBBox().height/2 + Graph.Renderer.Raphael.VMARGIN*2});
                    
                    // ===== 0 for collapsing node
                    var collapseX = r.text(5,5,Graph.Renderer.Raphael.COLLAPSE_TXT)
                    collapseX.attr({"font-size": 14, "font-family": "Arial, Helvetica, sans-serif", "fill" : "black", "stroke-width": 3});
                	
                	// collapse callbacks
                	collapseX.node.onmousedown = function (e) {
            			registerMouseDown(e);
            		};
            		
            		collapseX.node.onmouseup = function (pnode, graph, e) {
            			registerMouseUp(e);
            			if (mouseUpIsClick()) {
                            if (pnode.collapsed) {
                                Graph.Renderer.Raphael.prototype.uncollapseNode(pnode);
                            } else {
                                Graph.Renderer.Raphael.prototype.collapseNode(pnode);
                            }
            			}
            		}.bind(this, node, graph);
                   
                    labelN.push(collapseX);
                    collapseX.attr({"y": collapseX.getBBox().height/2 + Graph.Renderer.Raphael.VMARGIN*2});
                    
                	// === add the title
                	
					//var lt = r.text(5,5, node.parentclass + " [ " + node.id + " ]"); // make sure both the SPARQL name and type appear in header
					var lt = r.text(5,5, "\u2610 " + node.id ); // make sure both the SPARQL name and type appear in header
                	lt.attr({"font-size": 16, "font-family": "Arial, Helvetica, sans-serif"});
                	lt.attr({"x" : getLineX(lt.getBBox().width, Graph.Renderer.Raphael.HMARGIN)});
                	displayLabelOptions(lt, node.parentSNode.getDisplayOptions());
                	
                	// SNode
                	lt.node.onmousedown = function (e) {
            			registerMouseDown(e);
            		};
            		
            		lt.node.onmouseup = function (pnode, e) {
            			registerMouseUp(e);
            			if (mouseUpIsClick()) {
            				//node.parentSNode.toggleReturnType(lt);
            		    	pnode.parentSNode.callAsyncSNodeEditor(lt); 
            			}
            		}.bind("blank_this", node);

        			labelN.push(lt);	
        			lt.attr({"y" : getLineY(1, Graph.Renderer.Raphael.VMARGIN, labelN)});
        			var str = "value";
        			

                	for ( var i = 0; i < psize; i++){  		
                		var l = buildGraphNodeLabels(node.propLabels[i], r, i, Graph.Renderer.Raphael.HMARGIN+Graph.Renderer.Raphael.HTAB, Graph.Renderer.Raphael.VMARGIN, labelN, 0, node, true);
                    	displayLabelOptions(l, node.parentSNode.getPropertyItem(i).getDisplayOptions());

                	}
                	
                	// loop over the nodes
                	var ksize = node.nodeLabels.length;
                	
                	for ( var i = 0; i < ksize; i++){
                		var l = buildGraphNodeLabels(node.nodeLabels[i], r, i, Graph.Renderer.Raphael.HMARGIN+Graph.Renderer.Raphael.HTAB, Graph.Renderer.Raphael.VMARGIN, labelN, psize, node, false);
                    	displayLabelOptions(l, node.parentSNode.getNodeItem(i).getDisplayOptions());

                	}
                	
                	rect.attr({"width": Graph.Renderer.Raphael.HMARGIN + Graph.Renderer.Raphael.HMARGIN + Graph.Renderer.Raphael.HTAB + getMaxWidthArr(labelN)});
                    rect.attr({"height": getHeightArr(Graph.Renderer.Raphael.VMARGIN, labelN)});

                    deleteX.attr({"x" : rect.getBBox().width - deleteX.getBBox().width - Graph.Renderer.Raphael.HMARGIN });
                    collapseX.attr({"x" : deleteX.attrs.x - (Graph.Renderer.Raphael.HMARGIN * 2) - collapseX.getBBox().width} );
                	
                	shape = r.set();
                    
                	allsize = psize + ksize + Graph.Renderer.Raphael.HEADER_SIZE;
                    var whatever = shape.push(rect);
                    for( var i = 0; i < allsize; i++){
                    	labelN[i].id = node.id + "_" + i ;
                    	whatever = whatever.push(labelN[i]);
                    	node.subnodes.push( labelN[i] );
                    }
                    
                } 
                return shape;
            }
        }
        
        /* or check for an ajax representation of the nodes */
        if(node.shapes) {
            // TODO ajax representation evaluation
        }

        shape = node.render(this.r, node).hide();

        shape.attr({"fill-opacity": .6});
        /* re-reference to the node an element belongs to, needed for dragging all elements of a node */
        shape.items.forEach(function(item){ 
        	item.set = shape; 
        	if (item.type == "rect") {item.node.style.cursor = "move";}
        	else {item.node.style.cursor = "pointer"}; 
        });
        shape.mousedown(this.dragger);

        var box = shape.getBBox();
        shape.translate(Math.round(point[0]-(box.x+box.width/2)),Math.round(point[1]-(box.y+box.height/2)))
        //console.log(box,point);
        node.hidden || shape.show();
        node.shape = shape;
    },
    drawEdge: function(edge) {
        /* if this edge already exists the other way around and is undirected */
    	
        if(edge.backedge)
            return;
        if(edge.source.hidden || edge.target.hidden) {
            edge.connection && edge.connection.fg.hide() | edge.connection.bg && edge.connection.bg.hide();
            return;
        }
        /* if edge already has been drawn, only refresh the edge */
        if(!edge.connection) {
            edge.style && edge.style.callback && edge.style.callback(edge); // TODO move this somewhere else
            edge.connection = this.r.connection(edge.source.shape, edge.target.shape, edge.style);
            edge.connection.label[0].style.cursor = "pointer";
            edge.connection.label[0].onclick = function(e) {
            	e.source.parentSNode.callAsyncLinkEditor(e.style.label, e.target.parentSNode, e);

            }.bind(edge.connection.label[0], edge);
            
            
        } else {
        
	        if (typeof edge.render === "undefined") {
	        	edge.render = function(r, edge) {
	        		alert("edge.render");
	        	}
	        }
	        
	        //FIXME showing doesn't work well
	        edge.connection.fg.show();
	        edge.connection.bg && edge.connection.bg.show();
	        edge.connection.draw();
        }
        // test green
        var lt = edge.connection.label;
        displayEdgeLabelOptions(lt, edge.source.parentSNode.getEdgeDisplayOptions(edge.style.label, edge.target.parentSNode));

        return;
    }
};
Graph.Layout = {};
Graph.Layout.Spring = function(graph, width, height) {
    this.width = width || 400;
    this.height = height || 400;
    this.graph = graph;
    this.iterations =  10;
    this.maxRepulsiveForceDistance = 6;
    this.k = 2;
    this.c = 0.01;
    this.maxVertexMovement = 0.5;
    this.layout();
    this.totalNodeArea = 0;
};
Graph.Layout.Spring.prototype = {
		
	iterate :  function(n, renderer, callback) {
		 console.log(n);
		 
		 this.layoutIteration(); 
		 this.layoutCalcBounds();
		 renderer.draw();
		 
		 // recursion with a timer to break the thread to the GUi
		 if (n==50) {
			 // last time, clear layout information
			 // like none of this ever happened.
			 this.layoutClear();
			 callback();
			 return
		 } else {
			 setTimeout(this.iterate.bind(this, n+1, renderer, callback), 50);
		 }
	},
		
	// PEC: I added this to do iterative layout/draw so it looks cool
	layoutLive : function (renderer, callback) {
		 
		 this.layoutPrepare();      // it would be nice to start where nodes are, but mapping is tricky
		 
		 this.iterate(0, renderer, callback);
		 
	},
	
    layout: function() {
        this.layoutPrepare();
        for (var i = 0; i < this.iterations; i++) {
            this.layoutIteration();
        }
        this.layoutCalcBounds();
    },
    
    layoutPrepare: function() {
    	var keys = Object.keys(this.graph.nodes);
    	this.totalNodeArea = 0;
    	
        for (var i=0; i < keys.length; i++) {
            var node = this.graph.nodes[keys[i]];   // PEC fixed loop syntax bug
            
            node.layoutPosX = 0; 
            node.layoutPosY = 0; 
            node.layoutForceX = 0;
            node.layoutForceY = 0;
            
            this.totalNodeArea += node.shape.getBBox().height * node.shape.getBBox().width;
        }
    },
    
    // PEC: I added this to allow a single layout then revert to un-layout behavior
    layoutClear: function() {
    	var keys = Object.keys(this.graph.nodes);
        for (var i=0; i < keys.length; i++) {
            var node = this.graph.nodes[keys[i]];   // PEC fixed loop syntax bug
            
            node.layoutPosX = undefined;
            node.layoutPosY = undefined;
            node.layoutForceX = undefined;
            node.layoutForceY = undefined;
           
        }
        
    },
    
    layoutCalcBounds: function() {
        var minx = Infinity, maxx = -Infinity, miny = Infinity, maxy = -Infinity;

        for (i in this.graph.nodes) {
            var x = this.graph.nodes[i].layoutPosX;
            var y = this.graph.nodes[i].layoutPosY;
            
            if(x > maxx) maxx = x;
            if(x < minx) minx = x;
            if(y > maxy) maxy = y;
            if(y < miny) miny = y;
        }

        var factor = this.width * this.height / (6 * this.totalNodeArea + 1);
        this.graph.layoutMinX = minx;
        this.graph.layoutMaxX = maxx * factor;
        this.graph.layoutMinY = miny;
        this.graph.layoutMaxY = maxy * factor;
    },
    
    layoutIteration: function() {
        // Forces on nodes due to node-node repulsions

        var prev = new Array();
        for(var c in this.graph.nodes) {
            var node1 = this.graph.nodes[c];
            for (var d in prev) {
                var node2 = this.graph.nodes[prev[d]];
                this.layoutRepulsive(node1, node2);
                
            }
            prev.push(c);
        }
        
        // Forces on nodes due to edge attractions
        for (var i = 0; i < this.graph.edges.length; i++) {
            var edge = this.graph.edges[i];
            this.layoutAttractive(edge);             
        }
        
        // Move by the given force
        for (i in this.graph.nodes) {
            var node = this.graph.nodes[i];
            var xmove = this.c * node.layoutForceX;
            var ymove = this.c * node.layoutForceY;

            var max = this.maxVertexMovement;
            if(xmove > max) xmove = max;
            if(xmove < -max) xmove = -max;
            if(ymove > max) ymove = max;
            if(ymove < -max) ymove = -max;
            
            node.layoutPosX += xmove;
            node.layoutPosY += ymove;
            node.layoutForceX = 0;
            node.layoutForceY = 0;
        }
    },

    layoutRepulsive: function(node1, node2) {
        if (typeof node1 == 'undefined' || typeof node2 == 'undefined')
            return;
        var dx = node2.layoutPosX - node1.layoutPosX;
        var dy = node2.layoutPosY - node1.layoutPosY;
        var d2 = dx * dx + dy * dy;
        if(d2 < 0.01) {
            dx = 0.1 * Math.random() + 0.1;
            dy = 0.1 * Math.random() + 0.1;
            d2 = dx * dx + dy * dy;
        }
        var d = Math.sqrt(d2);
        if(d < this.maxRepulsiveForceDistance) {
            var repulsiveForce = this.k * this.k / d;
            node2.layoutForceX += repulsiveForce * dx / d;
            node2.layoutForceY += repulsiveForce * dy / d;
            node1.layoutForceX -= repulsiveForce * dx / d;
            node1.layoutForceY -= repulsiveForce * dy / d;
        }
    },

    layoutAttractive: function(edge) {
        var node1 = edge.source;
        var node2 = edge.target;
        
        var dx = node2.layoutPosX - node1.layoutPosX;
        var dy = node2.layoutPosY - node1.layoutPosY;
        var d2 = dx * dx + dy * dy;
        if(d2 < 0.01) {
            dx = 0.1 * Math.random() + 0.1;
            dy = 0.1 * Math.random() + 0.1;
            var d2 = dx * dx + dy * dy;
        }
        var d = Math.sqrt(d2);
        if(d > this.maxRepulsiveForceDistance) {
            d = this.maxRepulsiveForceDistance;
            d2 = d * d;
        }
        var attractiveForce = (d2 - this.k * this.k) / this.k;
        if(edge.attraction == undefined) edge.attraction = 1;
        attractiveForce *= Math.log(edge.attraction) * 0.5 + 1;
        
        node2.layoutForceX -= attractiveForce * dx / d;
        node2.layoutForceY -= attractiveForce * dy / d;
        node1.layoutForceX += attractiveForce * dx / d;
        node1.layoutForceY += attractiveForce * dy / d;
    }
};

Graph.Layout.Ordered = function(graph, order) {
    this.graph = graph;
    this.order = order;
    this.layout();
};
Graph.Layout.Ordered.prototype = {
    layout: function() {
        this.layoutPrepare();
        this.layoutCalcBounds();
    },
    
    layoutPrepare: function(order) {
        for (i in this.graph.nodes) {
            var node = this.graph.nodes[i];
            node.layoutPosX = 0;
            node.layoutPosY = 0;
        }
            var counter = 0;
            for (i in this.order) {
                var node = this.order[i];
                node.layoutPosX = counter;
                node.layoutPosY = Math.random();
                counter++;
            }
    },
    
    layoutCalcBounds: function() {
        var minx = Infinity, maxx = -Infinity, miny = Infinity, maxy = -Infinity;

        for (i in this.graph.nodes) {
            var x = this.graph.nodes[i].layoutPosX;
            var y = this.graph.nodes[i].layoutPosY;
            
            if(x > maxx) maxx = x;
            if(x < minx) minx = x;
            if(y > maxy) maxy = y;
            if(y < miny) miny = y;
        }

        this.graph.layoutMinX = minx;
        this.graph.layoutMaxX = maxx;

        this.graph.layoutMinY = miny;
        this.graph.layoutMaxY = maxy;
    }
};

/*
 * usefull JavaScript extensions, 
 */


// called from belmont and internally
// This design could use some further improvement
// -Paul
changeLabelText = function(label, newText) {
	label.attr({"text" : label.attrs.text.slice(0,1) + newText});
};

displayEdgeLabelOptions = function (label, displayBitmap) {
	if (displayBitmap == 0) {
		label.attr({"fill": "#000000", "stroke":"none"});
	} else if (displayBitmap == 1) {
		label.attr({"fill": "#008000", "stroke":"bold"});
	} else {
		label.attr({"fill": "#001DA0", "stroke":"bold"});
	} 
};

displayLabelOptions = function (label, displayBitmap) {
	// this code was added to Graph Dracula to allow the recoloring of label text
	// based on the selected actions. it is not default behavior.


	// note: apparently "else if" in javascript is flakey
	//  CYFTLUC-55 - PEC TODO we need to figure out how to use more than color
	var symbol;
	var text;
	var fill;
	
	// Do nothing if the label isn't pre-loaded with a special character in position 0
	if (label.attrs.text.charCodeAt(0) < 128) {
		return;
	}
	
	// Determine property of attribute and set fill and symbol
	isReturned = displayBitmap & 1;
	isConstrained = displayBitmap & 2;

	if (isConstrained && isReturned) {
		fill = "#001DA0";          // dark blue.    
		symbol = "\u25fc";
	} 
	else if (isConstrained) {
		fill = "#008000";          // green
		symbol = "\u2611";
	} 
	else if (isReturned) {
		fill = "#EA2400";          // orange
		symbol = "\u2612";         // X in box
	}
	else {
		fill = "#000000";          // black
		symbol = "\u2610";         // empty box
	}

	// apply the new character and fill color
	text = symbol + label.attrs.text.slice(1);
	label.attr({"fill" : fill, "text" : text});


};

function log(a) {console.log&&console.log(a);}

getLineX = function (boxwidth, margin){
	
	return boxwidth / 2 + margin;
};
getLineY = function (lineno, margin, textArray) {
	  var y = 0;
	 
	  for (var i = 0; i < lineno; i++) {
		    y += textArray[i].getBBox().height;
		    y += margin;
	  }
	  y += textArray[lineno].getBBox().height / 2;

	  return y;
};

getMaxWidth = function () {
	  var maxX =0;
		
	  for (var i = 0; i < arguments.length; i++) {
		    if(maxX <  arguments[i].getBBox().width){
		    	maxX = arguments[i].getBBox().width;
		    	
		    }
		    
	  }
	  return maxX;
	  
};

getMaxWidthArr = function (arrUsed) {
	  var maxX =0;
		
	  for (var i = 0; i < arrUsed.length; i++) {
		    if(maxX < arrUsed[i].getBBox().width){
		    	maxX = arrUsed[i].getBBox().width;
		    	
		    }
		    
	  }
	  return maxX;
	  
};
getHeightArr = function (ht, arrUsed) {
	  var y = 0;
	  var margin = ht;
	  
	  for (var i = 0; i < arrUsed.length; i++) {
		    	y += arrUsed[i].getBBox().height;
		    	y += margin;
	  }
	  return y;
};

getHeight = function () {
	  var y = 0;
	  var margin = arguments[0];
	  for (var i = 1; i < arguments.length; i++) {
		    	y += arguments[i].getBBox().height;
		    	y += margin;
	  }
	  return y;
}


/*
 * Raphael Tooltip Plugin
 * - attaches an element as a tooltip to another element
 *
 * Usage example, adding a rectangle as a tooltip to a circle:
 *
 *      paper.circle(100,100,10).tooltip(paper.rect(0,0,20,30));
 *
 * If you want to use more shapes, you'll have to put them into a set.
 *
 */

Raphael.el.tooltip = function (tp) {
    this.tp = tp;
    this.tp.o = {x: 0, y: 0};
    this.tp.hide();
    this.hover(
        function(event){ 
            this.mousemove(function(event){ 
                this.tp.translate(event.clientX - 
                                  this.tp.o.x,event.clientY - this.tp.o.y);
                this.tp.o = {x: event.clientX, y: event.clientY};
            });
            this.tp.show().toFront();
        }, 
        function(event){
            this.tp.hide();
            this.unmousemove();
        });
    return this;
};

/* For IE */
if (!Array.prototype.forEach)
{
  Array.prototype.forEach = function(fun /*, thisp*/)
  {
    var len = this.length;
    if (typeof fun != "function")
      throw new TypeError();

    var thisp = arguments[1];
    for (var i = 0; i < len; i++)
    {
      if (i in this)
        fun.call(thisp, this[i], i, this);
    }
  };
}
//************* Special mouse functions *****************

var gMouseDownLoc = "";
var gMouseUpLoc = "";

var buildMouseLocation = function(e) {
	return e.clientX.toString() + ":" + e.clientY.toString();
};
var registerMouseDown = function(e) {
	if (e.which == 1) {
		gMouseDownLoc = buildMouseLocation(e);
	} else {
		gMouseDownLoc = "";
	}
};
var registerMouseUp = function(e) {
	if (e.which == 1) {
		gMouseUpLoc = buildMouseLocation(e);
	} else {
		gMouseUpLoc = "";
	}
};
var mouseUpIsClick = function() {
	return (gMouseUpLoc.length > 0 && gMouseUpLoc == gMouseDownLoc);
};
var mouseUpIsDrag = function() {
	return (gMouseUpLoc.length > 0 && gMouseUpLoc != gMouseDownLoc);
};

//************* End mouse functions *****************

var buildGraphNodeLabels = function(propLabelStr, r, i, hMargin, vMargin, labelN, psize, node, isproperty){
	// this method builds the actual labels used in the stacked boxes that represent each of the 
	// classes shown in the UI. it also attaches callbacks to  make various operations work.

	var itemLabel = r.text(5,5, "\u2610 " + propLabelStr );
	labelN.push(itemLabel);          		
    itemLabel.attr({"x" : getLineX(itemLabel.getBBox().width, hMargin)});
    itemLabel.attr({"y" : getLineY(psize + i + 2, vMargin, labelN)});
    
    // the folowing code declares the callbacks as un-named methods to cheat around 
    // not being able to directly reference the original generator from the callback call.
    // creating these on the fly allows us to embed some context about the object which originally was
    // in scope when the callback was assigned.
    
    // note: the Belmont nodes and Dracula nodes are linked circularly. this was needed so we can 
    // make some of the callbacks and cross-scope chaining work.
    
    
    if(isproperty){
		// propertyItem labels
		itemLabel.node.onmousedown = function (e) {
			registerMouseDown(e);
		};
		
		itemLabel.node.onmouseup = function (pItemLabel, pPropLabelStr, pNode, e) {
			registerMouseUp(e);
			if (mouseUpIsClick()) {
				var keyname = propLabelStr.split(" ")[0];
		    	pNode.parentSNode.callAsyncPropEditor(keyname, pItemLabel); 
			}
		}.bind("blank_this", itemLabel, propLabelStr, node);
		
    } else {
    	// nodeItem labels
		itemLabel.node.onmousedown = function (e) {
			registerMouseDown(e);
		};
		
		itemLabel.node.onmouseup = function (index, e) { 
			registerMouseUp(e);
			if (mouseUpIsClick()) {
		    	node.parentSNode.callAsyncLinkBuilder(index);
		    	
		    	// TODO: need code to un-color this when paths SNodes are deleted
		    	//itemLabel.attr({"fill" : "#FF0000", "stroke-width": 3});	
			}
    	
		}.bind("non-this", i);
		
		// Not sure why these don't do anything.
		// Instead Rederer d.onmousemove up around line 242
		itemLabel.node.ondragstart = function (e) {
			console.log("dragstart");
			e.preventDefault && e.preventDefault();
		};
		
		itemLabel.node.ondrag = function (e) {
			console.log("drag");
			e.preventDefault && e.preventDefault();
		};
		
		itemLabel.node.ondragend = function (e) {
			console.log("dragend");
			e.preventDefault && e.preventDefault();
		};
		//=======end do nothing ====
    }
    return itemLabel;
}