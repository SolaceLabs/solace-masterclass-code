document.addEventListener("DOMContentLoaded", function () {
    function tryInitializeOrderStatusConsumer() {
        if (typeof brokerConnected !== "undefined" && brokerConnected &&
            document.querySelectorAll("#orderTableBody tr").length > 0 &&
            !window.orderStatusConsumerInitialized) {
            if (typeof initializeOrderStatusConsumer === "function") {
                initializeOrderStatusConsumer(window.solaceConnectionParameters);
                window.orderStatusConsumerInitialized = true;
            }
        }
    }
    tryInitializeOrderStatusConsumer();
    var createBasketForm = document.getElementById("createBasketForm");
    var createBasketBtn = document.getElementById("createBasketBtn");
    if (createBasketForm && createBasketBtn) {
        createBasketBtn.addEventListener("click", function (e) {
            e.preventDefault();
            createBasketBtn.disabled = true;
            fetch("/createNewBasket", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded",
                    "Accept": "application/json"
                }
            })
            .then(async response => {
                const contentType = response.headers.get("content-type");
                if (!response.ok) {
                    throw new Error("Failed to create basket");
                }
                if (contentType && contentType.indexOf("application/json") !== -1) {
                    return response.json();
                } else {
                    throw new Error("Server did not return JSON. Please check backend implementation.");
                }
            })
            .then(data => {
                document.getElementById("createBasketResult").textContent = "Basket created successfully";
                if (data && data.id) {
                    var tbody = document.getElementById("orderTableBody");
                    var row = document.createElement("tr");
                    row.innerHTML = `
                        <td>${data.id || ""}</td>
                        <td>${data.customerId || ""}</td>
                        <td>${data.state || ""}</td>
                        <td>${data.product || ""}</td>
                        <td>${data.quantity || ""}</td>
                        <td>${data.price || ""}</td>
                    `;
                    tbody.appendChild(row);
                    var orderTable = document.getElementById("orderTable");
                    if (orderTable.classList.contains("d-none")) {
                        orderTable.classList.remove("d-none");
                    }
                    // If this is the first row, try to initialize the consumer
                    if (tbody.rows.length === 1) {
                        tryInitializeOrderStatusConsumer();
                    }
                }
            })
            .catch(error => {
                document.getElementById("createBasketResult").textContent = "Error creating basket: " + error.message;
                console.error("Error creating basket:", error);
            })
            .finally(() => {
                createBasketBtn.disabled = false;
            });
        });
    }
});
