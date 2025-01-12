/**
 ** Copyright 2016-17 General Electric Company
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

// This file is not a typical javascript object, but a script
// that needs to be loaded by the HTML.
// Its other half must also be loaded by the HTML:  sparqlform.js

var gQueryClient = null;
var gAvoidQueryMicroserviceFlag = false;
var gLoadDialog = null;
var gQueryResults = null;



//----- e d c ------/
getOntTriggerClassNames = function() {
	return [];
};

isOntTriggerClassName = function(uri) {
	return false;
};

removeTrigger = function(nodegroup) {
};

//----- Constraints -----/
formConstraintsNew = function() {
	gFormConstraint = null;
};

formConstraintsInit = function() {
};

formConstraintsUpdateTable = function() {
};

formConstraintsValidate = function () {
	return 0;
};

formConstraintsGet = function () {
	return null;
};

formConstraintJson = function () {
	return {};
};

formConstraintSetFromJson = function(json) {
};

//----- Constraint (as in Belmont Constraint) dialog -----//
launchConstraintDialog = function(item, failureCallback, ghostItem, ghostNodegroup, textId) {

		 // new tries to check and set optional when item is a SNode instead of propItem
		// constraints are untested
    require(['sparqlgraph/js/msiclientquery',
            'sparqlgraph/js/modalitemdialog'],
            function(MsiClientQuery,ModalItemDialog) {
		var clientOrInterface;

		if (gAvoidQueryMicroserviceFlag) {
			clientOrInterface = gConn.getDefaultQueryInterface();
		}
		else {
			clientOrInterface = new MsiClientQuery(Config.services.query.url, gConn.getDefaultQueryInterface(), failureCallback);
		}

        // PEC HERE
        // Removing EDC seems to be done in the modal item dialog
        // This is bad.
        // sparqlformlocal could simply have removeEDC.  Rest could be in sparqlform.js
		var dialog= new ModalItemDialog(item, gNodeGroup, clientOrInterface, itemDialogCallback, { "textId" : textId }, ghostItem, ghostNodegroup);
		dialog.show(true);
	});
};

doAbout = function() {
    require(['local/sparqlformconfig','sparqlgraph/js/modaliids'],
            function(Config, ModalIidx) {
		ModalIidx.alert("About SparqlForm", Config.help.aboutHtml + "<br>" + Config.help.legalNoticeHtml);
    });
};

doTest = function() {
    require(['sparqlgraph/js/modaliids'],
            function(ModalIidx) {
		ModalIidx.alert("Testing", "No tests to run.");
    });
};


	//-----  query directly -----//

var AQM_COOKIE = "avoidMs";
doAvoidQueryMicroservice = function(flag) {
    require(['sparqlgraph/js/cookiemanager'], function() {
		gAvoidQueryMicroserviceFlag = flag;
		var cookies = new CookieManager(document);
		cookies.setCookie(AQM_COOKIE, gAvoidQueryMicroserviceFlag ? "t" : "f");
	});
};

initAvoidQueryMicroservice = function() {
    require(['sparqlgraph/js/cookiemanager'], function() {
		var cookies = new CookieManager(document);
		var flag = cookies.getCookie(AQM_COOKIE);
		gAvoidQueryMicroserviceFlag = flag && flag == "t";
		document.getElementById("chkboxAvoidMicroSvc").checked = gAvoidQueryMicroserviceFlag;
    });
};

//-----  on load async callback chain functions -----//
doFileLoad = function() {
	gLoadDialog.loadDialog(gConn, loadSuccess0);
};

loadSuccess0 = function(connProfile, directFlag, optCallback) {
    require(['local/sparqlformconfig',
             'sparqlgraph/js/msiclientontologyinfo',
             'sparqlgraph/js/msiclientquery',

             'sparqlgraph/js/ontologyinfo'],
             function(Config,MsiClientOntologyInfo,MsiClientQuery ) {
    	// Callback from the load dialog
    	var callback = (typeof optCallback === "undefined") ? function(){} : optCallback;

    	// Clean out existing GUI
        initServices();
		initForm();
		clearResults();
		enableButton('btnFormExecute'); // browser sometimes caches this disabled if user reloaded while a query is running.  wow.

    	// Get connection info from dialog return value
    	gConn = connProfile;   // instead of gConnSetup()

        if (gConn == null) {
            loadFailure("No connection is loaded");
        } else {
        	gQueryClient =       new MsiClientQuery(Config.services.query.url, gConn.getDefaultQueryInterface());

        	kdlLogEvent("SF Loading", "connection", gConn.toString());

        	var queryServiceUrl = gAvoidQueryMicroserviceFlag ? null : Config.services.query.url;

    		gOInfo = new OntologyInfo();

            var oInfoClient = new MsiClientOntologyInfo(Config.services.ontologyInfo.url, loadFailure);
            gOInfo.loadFromService(oInfoClient, gConn, setStatus, function(){ loadSuccess2(); callback();}, loadFailure);
        }
    });
};

loadSuccess2 = function() {
	document.getElementById("titleSpan").innerHTML = "SparqlForm: "
		+ gConn.name
		+ "<br><small><i>powered by GRC Knowledge Discovery Lab</i></small>";

	kdlLogEvent("SF: Load Success", "endpoint", gConn.name);
	loadDone();
};

loadFailure = function(msg) {
    require([
             'sparqlgraph/js/ontologyinfo'],
             function() {
		alertUser("failure: " + msg);
		gOInfo = new OntologyInfo();
		kdlLogEvent("SF: Load Failure", "endpoint", gConn.name);
		loadDone();
    });
};

loadDone = function() {
	// after succesfully loading the ontology
	setStatus("");
	initNodeGroup();
	initOTree();
};

logAndAlert = function (msgHtml, optTitle) {
    require(['sparqlgraph/js/modaliidx'],
            function(ModalIidx) {
    	var title = typeof optTitle === "undefined" ? "Alert" : optTitle
    	kdlLogEvent("SF: alert", "message", msgHtml);
    	ModalIidx.alert(title, msgHtml);
    });
};

	// ****   Start on load proceedure ****

onLoad = function () {
    require(['local/sparqlformconfig',
             'sparqlgraph/js/msiclientnodegroupservice',
             'sparqlgraph/js/modalloaddialog',

             'sparqlgraph/js/ontologyinfo',
             'sparqlgraph/js/sparqlform'],
             function(Config, MsiClientNodeGroupService,ModalLoadDialog) {

		// In the past:  isFirefox() was done here
		initAvoidQueryMicroservice();

		// globals
        var ngClient = new MsiClientNodeGroupService(Config.services.nodeGroup.url);
		gLoadDialog = new ModalLoadDialog(document, "gLoadDialog", ngClient);

		// load last connection
		var conn = gLoadDialog.getLastConnectionInvisibly();
		if (conn) {
			loadSuccess0(conn);
		} else {
            initServices();
        }

		// PEC TODO: load cookie or launch dialog
		setStatus("");
    });
};

onLoad();
