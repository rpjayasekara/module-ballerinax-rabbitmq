// Copyright (c) 2020 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
//
// WSO2 Inc. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/lang.'string;
import ballerina/log;
import ballerina/lang.runtime as runtime;
import ballerina/test;

Client? rabbitmqChannel = ();
Listener? rabbitmqListener = ();
const QUEUE = "MyQueue";
const ACK_QUEUE = "MyAckQueue";
const SYNC_NEGATIVE_QUEUE = "MySyncNegativeQueue";
const DATA_BINDING_QUEUE = "MyDataQueue";
string asyncConsumerMessage = "";
string replyMessage = "";
string dataBindingMessage = "";
string REPLYTO = "replyHere";

@test:BeforeSuite
function setup() {
    log:printInfo("Creating a ballerina RabbitMQ channel.");
    Client newClient = checkpanic new(DEFAULT_HOST, DEFAULT_PORT);
    rabbitmqChannel = newClient;
    Client? clientObj = rabbitmqChannel;
    if (clientObj is Client) {
        string? queue = checkpanic clientObj->queueDeclare(QUEUE);
        string? dataBindingQueue = checkpanic clientObj->queueDeclare(DATA_BINDING_QUEUE);
        string? syncNegativeQueue = checkpanic clientObj->queueDeclare(SYNC_NEGATIVE_QUEUE);
        string? ackQueue = checkpanic clientObj->queueDeclare(ACK_QUEUE);
        string? replyQueue = checkpanic clientObj->queueDeclare(REPLYTO);
    }
    Listener lis = checkpanic new(DEFAULT_HOST, DEFAULT_PORT);
    rabbitmqListener = lis;
}

@test:Config {
    groups: ["rabbitmq"]
}
public function testClient() {
    boolean flag = false;
    Client? con = rabbitmqChannel;
    if (con is Client) {
        flag = true;
    }
    Client newClient = checkpanic new(DEFAULT_HOST, DEFAULT_PORT);
    checkpanic newClient.close();
    test:assertTrue(flag, msg = "RabbitMQ Connection creation failed.");
}

@test:Config {
    dependsOn: [testClient],
    groups: ["rabbitmq"]
}
public function testProducer() returns error? {
    Client? channelObj = rabbitmqChannel;
    if (channelObj is Client) {
        string message = "Hello from Ballerina";
        check channelObj->publishMessage({ content: message.toBytes(), routingKey: QUEUE });
        check channelObj->queuePurge(QUEUE);
    }
}

@test:Config {
    groups: ["rabbitmq"]
}
public function testListener() {
    boolean flag = false;
    Listener? channelListener = rabbitmqListener;
    if (channelListener is Listener) {
        flag = true;
    }
    test:assertTrue(flag, msg = "RabbitMQ Listener creation failed.");
}

@test:Config {
    dependsOn: [testProducer],
    groups: ["rabbitmq"]
}
public function testSyncConsumer() returns error? {
    string message = "Testing Sync Consumer";
    produceMessage(message, QUEUE);
    Client? channelObj = rabbitmqChannel;
    if (channelObj is Client) {
        Message getResult = check channelObj->consumeMessage(QUEUE);
        string messageContent = check 'string:fromBytes(getResult.content);
        test:assertEquals(messageContent, message, msg = "Message received does not match.");
    }
}

@test:Config {
    dependsOn: [testListener, testSyncConsumer],
    groups: ["rabbitmq"]
}
public function testAsyncConsumer() {
    string message = "Testing Async Consumer";
    produceMessage(message, QUEUE);
    Listener? channelListener = rabbitmqListener;
    if (channelListener is Listener) {
        checkpanic channelListener.attach(asyncTestService);
        checkpanic channelListener.attach(replyService);
        checkpanic channelListener.'start();
        runtime:sleep(5);
        test:assertEquals(asyncConsumerMessage, message, msg = "Message received does not match.");
    }
}

@test:Config {
    dependsOn: [testListener, testAsyncConsumer],
    groups: ["rabbitmq"]
}
public function testAcknowledgements() {
    string message = "Testing Message Acknowledgements";
    produceMessage(message, ACK_QUEUE);
    Listener? channelListener = rabbitmqListener;
    if (channelListener is Listener) {
        checkpanic channelListener.attach(ackTestService);
        runtime:sleep(2);
    }
}

@test:Config {
    dependsOn: [testAsyncConsumer, testAcknowledgements],
    groups: ["rabbitmq"]
}
public function testOnRequest() {
    string message = "Hello from the other side!";
    produceMessage(message, QUEUE, REPLYTO);
    Listener? channelListener = rabbitmqListener;
    if (channelListener is Listener) {
        runtime:sleep(5);
        test:assertEquals(asyncConsumerMessage, message, msg = "Message received does not match.");
        test:assertEquals(replyMessage, "Hello Back!!", msg = "Reply message received does not match.");

    }
}

Service asyncTestService =
@ServiceConfig {
    queueName: QUEUE
}
service object {
    remote function onMessage(Message message) {
        string|error messageContent = 'string:fromBytes(message.content);
        if (messageContent is string) {
            asyncConsumerMessage = <@untainted> messageContent;
            log:printInfo("The message received: " + messageContent);
        } else {
            log:printError("Error occurred while retrieving the message content.", 'error = messageContent);
        }
    }

    remote function onRequest(Message message) returns string {
        string|error messageContent = 'string:fromBytes(message.content);
        if (messageContent is string) {
            asyncConsumerMessage = <@untainted> messageContent;
            log:printInfo("The message received in onRequest: " + messageContent);
        } else {
            log:printError("Error occurred while retrieving the message content.", 'error = messageContent);
        }
        return "Hello Back!!";
    }
};

Service ackTestService =
@ServiceConfig {
    queueName: ACK_QUEUE,
    autoAck: false
}
service object {
    remote function onMessage(Message message, Caller caller) {
        checkpanic caller->basicAck();
    }
};

Service replyService =
@ServiceConfig {
    queueName: REPLYTO
}
service object {
    remote function onMessage(Message message) {
        string|error messageContent = 'string:fromBytes(message.content);
        if (messageContent is string) {
            replyMessage = <@untainted> messageContent;
            log:printInfo("The reply message received: " + messageContent);
        } else {
            log:printError("Error occurred while retrieving the message content.", 'error = messageContent);
        }
    }
};

function produceMessage(string message, string queueName, string? replyToQueue = ()) {
    Client? clientObj = rabbitmqChannel;
    if (clientObj is Client) {
        if (replyToQueue is string) {
            checkpanic clientObj->publishMessage({ content: message.toBytes(), routingKey: queueName,
                    properties: { replyTo: replyToQueue }});
        } else {
            checkpanic clientObj->publishMessage({ content: message.toBytes(), routingKey: queueName });
        }
    }
}
