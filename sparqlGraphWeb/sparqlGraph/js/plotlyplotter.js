/**
 ** Copyright 2021 General Electric Company
 **
 ** Authors:  Paul Cuddihy, Jenny Williams
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

define([	// properly require.config'ed   bootstrap-modal
        	'sparqlgraph/js/iidxhelper',
            'sparqlgraph/js/msiresultset',
            'sparqlgraph/js/msiclientutility',

            'plotly/plotly-latest.min'
            //                       OR should we presume the internet is available?  and pull from there?
			// shimmed
		],

    function(IIDXHelper, MsiResultSet, MsiClientUtility, Plotly) {

        /*
            Plot Spec = { name: "", type: "", spec: { data: [{},{}], layout: {}, config: {} } }
         */
        var PlotlyPlotter = function (plotSpec) {
            this.spec = plotSpec;
        };

        PlotlyPlotter.prototype = {
            CONSTANT : 1,

            getName : function () {
                return this.spec.name;
            },

            addPlotToDiv : function(div, tableRes) {

                var plotDiv = document.createElement("div");
                div.appendChild(plotDiv);

                var utilityClient = new MsiClientUtility(g.service.utility.url);
                utilityClient.execProcessPlotSpec(this.spec, tableRes.getTable(), this.processSpecSuccess.bind(this, plotDiv));
            },

            processSpecSuccess : function(div, msiRes) {
                var data = msiRes.getSimpleResultField("plot").spec.data;
                var layout = msiRes.getSimpleResultField("plot").spec.layout;
                var config = msiRes.getSimpleResultField("plot").spec.config;
                Plotly.newPlot( div, data, layout, config );
            }
        };

        return PlotlyPlotter;            // return the constructor
	}
);
