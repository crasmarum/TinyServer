  <%!   
  java.lang.String getItem(int indx) {
	  return new com.example.web.TestClass().getMessage() + indx;
  }
  %>

<!DOCTYPE html>
<html lang="en">
  <head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Hello Tiny JSP</title>
  </head>
  <body>
  
<table style="border: 1px solid lightblue; border-collapse: collapse;">
  <tr>
  	<td style="padding: 10px;font-weight: bold;">
  	Example script:
  	</td>
    <td style="padding: 10px;">
   
    <p> <%= (new java.util.Date()).toLocaleString() %> </p>
    
    </td>
  </tr>
</table>
<br>

<style>
.label-wrap{
  display:flex;
  padding: 5px;
}
</style>

<table style="border: 1px solid lightblue; border-collapse: collapse;">
  <tr>
  	<td style="padding: 10px;font-weight: bold;">
  	Example submit form to servlet:
  	</td>
    <td style="padding: 10px;">
    
    <form style="margin: 0; padding: 0;" action="/TestServlet" method="post" id="usrform">
    <div class='label-wrap'>
	    <label for="name">Name:&nbsp;</label>
	    <input type="text" name="name"  required>
	</div>
	
	<input type="submit" value="Submit">
	</form>
    
    </td>
    
    <td>
        <b><%= request.attributes.get("msg") == null ? "" : request.attributes.get("msg") %></b>
    </td>
  </tr>
</table>
<br>

<table style="border: 1px solid lightblue; border-collapse: collapse;">
  <tr>
  	<td style="padding: 10px;font-weight: bold;">
  	Example get JSON asynchronously:
  	</td>
    <td style="padding: 10px;">

    <button id="asyncBtn" onclick="fetchVarz()">Get JSON</button>
    &nbsp;
    <button id="asyncAlertBtn" onclick="fetchVarzAlert()">Async</button>

    </td>
  </tr>
</table>
<br>


<div id="varz-container" style="display:none; margin-top: 16px;">
  <table id="varz-table">
    <thead>
      <tr>
        <th>Metric</th>
        <th>Value</th>
      </tr>
    </thead>
    <tbody id="varz-tbody"></tbody>
  </table>
</div>

<style>
  #varz-table {
    border-collapse: collapse;
    font-family: Arial, sans-serif;
    font-size: 14px;
    min-width: 320px;
    box-shadow: 0 2px 6px rgba(0,0,0,0.12);
    border-radius: 6px;
    overflow: hidden;
  }

  #varz-table thead tr {
    background-color: #1a6fa8;
    color: #ffffff;
    text-align: left;
  }

  #varz-table th,
  #varz-table td {
    padding: 10px 18px;
  }

  #varz-table tbody tr:nth-child(odd) {
    background-color: #ffffff;
  }

  #varz-table tbody tr:nth-child(even) {
    background-color: #d6eaf8;
  }

  #varz-table tbody tr:hover {
    background-color: #aed6f1;
    transition: background-color 0.2s;
  }

  #varz-table td:first-child {
    font-weight: bold;
    color: #1a5276;
  }
</style>

<script>
  const LABELS = {
    maxMemoryM:       "Max Memory (MB)",
    usedMemoryM:      "Used Memory (MB)",
    noThreads:        "Threads",
    noVirtualThreads: "Virtual Threads",
    queriesPerMinute: "Queries / Minute"
  };

  async function fetchVarzAlert() {
    try {
      const response = await fetch('/varz', {
        method: 'GET',
        headers: { 'Accept': 'application/json' }
      });
      const text = await response.text();
      alert(text);
    } catch (error) {
      alert(`Error fetching /varz:\n${error.message}`);
    }
  }

  async function fetchVarz() {
    try {
      const response = await fetch('/testjson', {
        method: 'GET',
        headers: { 'Accept': 'application/json' }
      });

      if (!response.ok) {
        throw new Error(`HTTP error: ${response.status} ${response.statusText}`);
      }

      const data = await response.json();
      renderTable(data);
    } catch (error) {
      alert(`Error fetching /varz:\n${error.message}`);
    }
  }

  function renderTable(data) {
    const tbody = document.getElementById('varz-tbody');
    tbody.innerHTML = '';

    for (const [key, value] of Object.entries(data)) {
      const label = LABELS[key] || key;
      const row = document.createElement('tr');
      row.innerHTML = `<td>${label}</td><td>${value}</td>`;
      tbody.appendChild(row);
    }

    document.getElementById('varz-container').style.display = 'block';
  }
</script>

<br>
<table style="border: 1px solid lightblue; border-collapse: collapse;">
  <tr>
  	<td style="padding: 10px;font-weight: bold;">
  	Example Java code in JSP:
  	</td>
    <td style="padding: 10px;">
    <% for (int i = 0; i < 3; i++) { %>
    	<p><%= getItem(i) %></p>
    <% } %>
    
    </td>
  </tr>
</table>
<br>
    

</body>
</html>
