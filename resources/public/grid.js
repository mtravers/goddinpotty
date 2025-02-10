document.addEventListener("DOMContentLoaded", function () {
    // Column definitions
    // TODO renderes, privacy designator, widths
    // TODO default to reverse sort by Date
    const columnDefs = [
        // { field: "id", headerName: "ID", sortable: true, filter: true }, 
        { field: "title", headerName: "Title", sortable: true, filter: true,
	  cellRenderer: function(params) {
              return `<a href="${params.data.url}" target="_blank">${params.data.title}</a>`;
	  }},
	{ field: "date", headerName: "Date", sortable: true, filter: true },
	{ field: "depth", headerName: "Depth", sortable: true, filter: true },
	{ field: "size", headerName: "Size", sortable: true, filter: true }
    ];

    // Grid options
    // TODO narrow rows
    const gridOptions = {
        columnDefs: columnDefs,
        rowData: [],
        pagination: true,
        defaultColDef: {
            sortable: true,
            filter: true
        }
    };

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
