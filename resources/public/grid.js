document.addEventListener("DOMContentLoaded", function () {
    // Column definitions
    const columnDefs = [
        { field: "id", headerName: "ID", sortable: true, filter: true },
        { field: "title", headerName: "Title", sortable: true, filter: true }
    ];

    // Grid options
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
