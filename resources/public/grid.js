document.addEventListener("DOMContentLoaded", function () {
    // Column definitions
    // TODO  privacy designator
    const columnDefs = [
        // { field: "id", headerName: "ID", sortable: true, filter: true }, 
        { field: "title", headerName: "Title",
	  maxWidth: 350,
	  cellRenderer: function(params) {
              return `<a href="${params.data.url}" target="_blank">${params.data.title}</a>`;
	  }},
	{ field: "date", headerName: "Date", sort: "desc"},
	{ field: "depth", headerName: "Depth" },
	{ field: "size", headerName: "Size" }
    ];

    // Grid options
    const gridOptions = {
        columnDefs: columnDefs,
        rowData: [],
	rowHeight: 20,
        pagination: true,
        defaultColDef: {
            sortable: true,
            filter: true,
        },
	onFirstDataRendered: function(params) {
	    params.api.autoSizeAllColumns();
	    
	}};

    // Create the grid
    const eGridDiv = document.getElementById("myGrid");
    var grid = new agGrid.createGrid(eGridDiv, gridOptions);

    // Fetch data from JSON URL
    fetch("assets/index.js")
        .then(response => response.json())
        .then(data => {
            grid.setGridOption('rowData', data);
        })
        .catch(error => console.error("Error fetching data:", error));
});
