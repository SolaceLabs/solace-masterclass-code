function initializeOrderStatusConsumer(solaceConnectionParameters) {
    if (!solaceConnectionParameters || !solaceConnectionParameters.hostUrl) return;

    var wsHostUrl = transformHostUrlForWebSocket(solaceConnectionParameters.hostUrl);
    var factoryProps = new solace.SolclientFactoryProperties();
    solace.SolclientFactory.init(factoryProps);

    var session = solace.SolclientFactory.createSession({
        url: wsHostUrl,
        vpnName: solaceConnectionParameters.vpnName,
        userName: solaceConnectionParameters.userName,
        password: solaceConnectionParameters.password
    });

    var queueName = "all-order-updates";
    var messageConsumer = null;
    var retryDelay = 5000; // 5 seconds

    function cleanupConsumer() {
        if (messageConsumer) {
            try {
                messageConsumer.disconnect();
            } catch (e) {
                console.warn("Error disconnecting consumer:", e);
            }
            messageConsumer = null;
        }
    }

    function connectConsumer() {
        cleanupConsumer();
        messageConsumer = session.createMessageConsumer({
            queueDescriptor: {name: queueName, type: solace.QueueType.QUEUE},
            acknowledgeMode: solace.MessageConsumerAcknowledgeMode.CLIENT,
            createIfMissing: false
        });

        messageConsumer.on(solace.MessageConsumerEventName.UP, function () {
            console.log("Connected to queue:", queueName);
        });

        messageConsumer.on(solace.MessageConsumerEventName.MESSAGE, function (message) {
            try {
                var payload;
                var topicName;
                payload = message.getSdtContainer().getValue();
                topicName = message.getDestination().getName();
                console.log("Received message on topic:", topicName, "with payload:", payload);

                // Only parse if payload is a string and not empty
                if (typeof payload === "string" && payload.trim().length > 0) {
                    var order = JSON.parse(payload);
                    console.log("Parsed order object:", order);
                    updateOrderRow(order,topicName);
                }
                message.acknowledge();
            } catch (e) {
                console.error("Failed to process message:", e, message);
                // Optionally acknowledge or dead-letter the message here
            }
        });

        messageConsumer.on(solace.MessageConsumerEventName.CONNECT_FAILED_ERROR, function () {
            console.error("Failed to connect guaranteed consumer. Retrying in " + retryDelay + "ms.");
            setTimeout(connectConsumer, retryDelay);
        });

        messageConsumer.on(solace.MessageConsumerEventName.DOWN, function () {
            console.warn("Message consumer down. Will reconnect.");
            setTimeout(connectConsumer, retryDelay);
        });

        messageConsumer.connect();
    }

    session.on(solace.SessionEventCode.UP_NOTICE, function () {
        console.log("Session is up. Connecting consumer...");
        connectConsumer();
    });

    session.on(solace.SessionEventCode.CONNECT_FAILED_ERROR, function () {
        console.error("Failed to connect to Solace broker. Retrying in " + retryDelay + "ms.");
        setTimeout(function () {
            session.connect();
        }, retryDelay);
    });

    session.on(solace.SessionEventCode.DOWN_ERROR, function () {
        console.error("Session down error. Retrying in " + retryDelay + "ms.");
        cleanupConsumer();
        setTimeout(function () {
            session.connect();
        }, retryDelay);
    });

    session.on(solace.SessionEventCode.DISCONNECTED, function () {
        console.error("Session disconnected. Retrying in " + retryDelay + "ms.");
        cleanupConsumer();
        setTimeout(function () {
            session.connect();
        }, retryDelay);
    });

    session.connect();
}

function transformHostUrlForWebSocket(hostUrl) {
    if (!hostUrl) return hostUrl;
    return hostUrl
        .replace(/^tcps:\/\//, "wss://")
        .replace(/:55443$/, ":443");
}

function updateOrderRow(eventObj,topicName) {
    var orderId;
    var newState;

    if (topicName && topicName.includes("order")) {
        orderId = eventObj.id;
        newState = "VALIDATED";
    } else if (topicName && topicName.includes("payment")) {
        orderId = eventObj.orderId;
        newState = "PAYMENT_PROCESSED";
    } else if (topicName && topicName.includes("shipment")) {
        orderId = eventObj.orderId;
        newState = "SHIPPED";
    } else {
        return;
    }

    var $tbody = $("#orderTable tbody");
    var $existingRow = $tbody.find("tr").filter(function () {
        return $(this).find("td:first").text() === orderId;
    });

    if ($existingRow.length > 0) {
        $existingRow.find("td").eq(2).text(newState);
    }
}
window.initializeOrderStatusConsumer = initializeOrderStatusConsumer;
