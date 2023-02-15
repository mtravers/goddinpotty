
// Hacky code to support search widget
// TODO should have a visible "no results" indicater
// TODO might want to trim results to n or use threshold

// Treat / as separator
elasticlunr.tokenizer.seperator = /[\s\-\/]+/;

var index = elasticlunr(function () {
    this.addField('title');	// TODO adjust config
    this.addField('alias');
    this.addField('body');
    this.setRef('id');		// TODO is this used?
});

function Get(yourUrl){
    var Httpreq = new XMLHttpRequest(); // a new request
    Httpreq.open("GET",yourUrl,false);
    Httpreq.send(null);
    return Httpreq.responseText;          
}

function getDocs() {
    var docs = JSON.parse(Get("assets/index.js"));
    docs.forEach(function(doc) {
	index.addDoc(doc, false);
    });
}

function keypress(evt) {
// for search on enter, but doing it on all keys is better
//    if (evt.keyCode == 13) {
    doSearch();
}

var config = {
    fields: {
        title: {boost: 2},
	alias: {boost: 2},
        body: {boost: 1}
    },
    expand: true
}

function doSearch() {
    if (index.documentStore.length == 0) {
	getDocs();
    }
    var term = document.getElementById("searcht").value;
    var results = index.search(term, config);
    displayResults(results);
}

function insertText(container, text) {
    var node = document.createTextNode(text);
    container.appendChild(node);
    return node;
}

function insertLink(container, url, title) {
    var div = document.createElement('div');    
    div.setAttribute('class','searchentry');
    var link = document.createElement('a');
    link.setAttribute('href', url); 
    // link.setAttribute('target', '_blank'); 
    insertText(link, title);
    div.appendChild(link);
    container.appendChild(div);
    return link;
}

// TODO should limit to first n
function displayResults(results) {
    var out = document.getElementById('searcho');
    out.style.display = 'block'; 
    out.innerHTML = "";
    if (results.length == 0) {
	insertText(out, "No results") // TODO style
	    .setAttribute('class', 'searchnada')
    } else {
	results.forEach(function(result) {
	    insertLink(out, result.doc.url, result.doc.title);
	})
    }
}
    
// Following code is not search related, just here for convenience
// Persist collaps state of map

function maybeOpenMap() {
    var open = sessionStorage.getItem('map') == 'true';
    // var open = true;
    if (open) {
	document.getElementById('mapgraph').classList.add("show");
    }
}

document.addEventListener("DOMContentLoaded", maybeOpenMap);

function toggleMap() {
    var saved = sessionStorage.getItem('map');
    var open = null;
    if (saved == null) {
	var map = document.getElementById("mapgraph");
	open = (map.getAttribute("class") == 'show');	
    } else {
	open = saved == 'true';
    }
    console.log('map is', open);
    sessionStorage.setItem('map', !open);
}

// Also not search related: Expose elements tagged "local" if we are running from a local server
// TODO tie this to a cookie or something so it works on public pages (for me only)
function exposeLocals() {
    var locals = document.getElementsByClassName("local");
    for(var i = 0; i < locals.length; i++) {
	locals[i].classList.remove("local");
    }
}

function maybeExposeLocal() {
    if (window.document.location.hostname == "localhost") {
	exposeLocals();
    }
}

document.addEventListener("DOMContentLoaded", maybeExposeLocal);


