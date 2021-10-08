/**
 *  Flame Boss Driver
 *
 *  Copyright\u00A9 2021 Justin Leonard
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Change History:

**/

import groovy.json.*
import groovy.json.JsonBuilder

metadata
{
    definition(name: "Flameboss", namespace: "lnjustin", author: "Justin Leonard", importUrl: "")
    {
        capability "Refresh"
        capability "Initialize"
        capability "ContactSensor"
        capability "Switch"
        
        attribute "driverStatus", "string"
        
        attribute "cookerStatus", "string"
        
        attribute "pitTemp", "number"
        attribute "pitTempTarget", "number"
        attribute "pitAlarmTemp", "number"
        attribute "pitAlarmEnabled", "string"
        
        attribute "probe1Temp", "number"
        attribute "probe2Temp", "number"
        attribute "probe3Temp", "number"
        
        attribute "fanSpeed", "number"
        
        attribute "meat1DoneTemp", "number"
        attribute "meat1KeepWarmTemp", "number"
        attribute "meat2DoneTemp", "number"
        attribute "meat2KeepWarmTemp", "number"
        attribute "meat3DoneTemp", "number"
        attribute "meat3KeepWarmTemp", "number"
        
        command "setPitTemp", ["number"]
        command "setPitAlarm",[[name:"enabled*",type:"ENUM",description:"Alarm On/Off", constraints:["Enabled", "Disabled"]],
                               [name:"pitAlarmTemp*:",type:"NUMBER",desciption:"Pit Alarm Temp"]]        

        command "setMeat",[[name:"sensorNum*",type:"NUMBER",description:"Probe Number"],
                               [name:"doneTemp*:",type:"NUMBER",desciption:"Meat Done Temp"],
                               [name:"warmTemp*:",type:"NUMBER",desciption:"Meat Warm Temp"]]
    }
}

preferences
{
    section
    {
        
        input name: "deviceID", type: "String", title: "Flameboss Device ID", required: true
        input name: "flamebossServer", type: "String", title: "Flameboss Server Name", required: true
        input name: "username", type: "String", title: "Flameboss Username", required: true
        input name: "authToken", type: "String", title: "Auth Token", required: true
        input name: "units", type: "enum", options:["Celcius", "Fahrenheit"], title: "Units", defaultValue: "Fahrenheit"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def installed() {
    logDebug("installed()")
    
    initialize()
    runEvery1Hour(initialize)
}

def updated()
{
    configure()
}

def configure()
{    
    refresh()
}

def initialize() {
    logDebug("initialize()")
    
    if (interfaces.mqtt.isConnected()) {
        logDebug("Disconnecting...")
        interfaces.mqtt.disconnect()
    }
        
   
    runIn(1, connectToMqtt)
}


def uninstalled() {
    interfaces.mqtt.disconnect()
}

def parse(String description) {

        def decoded = interfaces.mqtt.parseMessage(description)
        log.debug "parse(${decoded})"
    
        if (decoded.topic == topicNameAttribute("open")) {
            def jsonSlurper = new JsonSlurper()
            def payload = jsonSlurper.parseText(decoded.payload)
            logDebug("Received JSON payload: ${payload}")
            if (payload.name == "protocol") turnOnDevice()
            if (payload.temps  != null) {
                def pitTemp = convertReceivedUnits(payload.temps[0])
                def probe1Temp = convertReceivedUnits(payload.temps[1])
                def probe2Temp = (payload.temps[2] != nulll) ? convertReceivedUnits(payload.temps[2]) : null
                def probe3Temp = (payload.temps[3] != nulll) ? convertReceivedUnits(payload.temps[3]) : null
                
                sendEvent(name: "pitTemp", value: pitTemp, isStateChange: true)
                sendEvent(name: "probe1Temp", value: probe1Temp, isStateChange: true)
                sendEvent(name: "probe2Temp", value: probe2Temp, isStateChange: true)
                sendEvent(name: "probe3Temp", value: probe3Temp, isStateChange: true)
            }
            if (payload.set_temp != null) {
                def targetPitTemp = convertReceivedUnits(payload.set_temp)
                sendEvent(name: "pitTempTarget", value: targetPitTemp, isStateChange: true)
            }
            if (payload.blower != null) {            
                def fanSpeed = payload.blower
                sendEvent(name: "fanSpeed", value: fanSpeed, isStateChange: true)
            }

        }
        else if (decoded.topic == topicNameAttribute("data")) {
            def jsonSlurper = new JsonSlurper()
            def payload = jsonSlurper.parseText(decoded.payload)

            if (payload.name == "meat_alarm") {
                if (payload.sensor == 1) {
                    sendEvent(name: "meat1DoneTemp", value: convertReceivedUnits(payload.done_temp), isStateChange: true)
                    sendEvent(name: "meat1KeepWarmTemp", value: convertReceivedUnits(payload.warm_temp), isStateChange: true)
                }
                else if (payload.sensor == 2) {
                    sendEvent(name: "meat2DoneTemp", value: convertReceivedUnits(payload.done_temp), isStateChange: true)
                    sendEvent(name: "meat2KeepWarmTemp", value: convertReceivedUnits(payload.warm_temp), isStateChange: true)
                }
                else if (payload.sensor == 3) {
                    sendEvent(name: "meat3DoneTemp", value: convertReceivedUnits(payload.done_temp), isStateChange: true)
                    sendEvent(name: "meat3KeepWarmTemp", value: convertReceivedUnits(payload.warm_temp), isStateChange: true)
                }
            }
            else if (payload.name == "pit_alarm") {
                if (payload.enabled != null) sendEvent(name: "pitAlarmEnabled", value: payload.enabled, isStateChange: true)
                if (payload.range != null) sendEvent(name: "pitAlarmTemp", value: convertReceivedUnits(payload.range), isStateChange: true)
            }
            else if (payload.name == "opened" || payload.name == "closed") sendEvent(name: "contact", value: payload.name, isStateChange: true, descriptionText: "Cooker is ${payload.name}")
            else if (payload.name == "disconnected") turnOffDevice()
        }
}

def convertReceivedUnits(value) {
    if (units == "Fahrenheit") {
        return Math.round((value * (9/50)) +32)
        // NOTE: this formula should have 9/5 instead of 9/50 - not sure why this is what makes it work?
    }
    else if (units == "Celcius") {
        return value
    }
}

def convertUnitsToSend(BigDecimal value) {
    if (units == "Fahrenheit") {
       def unitsC = Math.round((value - 32) * (50/9))
        // NOTE: this formula should have 5/9  instead of 50/9 - not sure why this is what makes it work?
        return unitsC
    }
    else if (units == "Celcius") {
        return value.intValueExact() 
    }
}

def setPitTemp(temp) {
    def commandPayload = new JsonOutput().toJson([name:"set_temp", value:"${convertUnitsToSend(temp)}"])
    logDebug("Setting Pit: ${commandPayload}")
    interfaces.mqtt.publish(topicNameCommand(), commandPayload)    
}

def setPitAlarm(enabled, temp) {
    def commandPayload = new JsonOutput().toJson([name:"pit_alarm", enabled: (enabled == "Enabled") ? true : false, range:"${temp}"])
    logDebug("Setting Pit Alarm: ${commandPayload}")
    interfaces.mqtt.publish(topicNameCommand(), commandPayload)    
}

def setMeat(sensorNum, doneTemp, warmTemp) {
    // TO DO: meat alarm action
    def commandPayload = new JsonOutput().toJson([name:"meat_alarm", sensor:sensorNum, action:"off", done_temp: convertUnitsToSend(doneTemp), warm_temp:convertUnitsToSend(warmTemp)])
    logDebug("Setting meat: ${commandPayload}")
    interfaces.mqtt.publish(topicNameCommand(), commandPayload)    
}


def mqttClientStatus(String status) {
    logDebug("mqttClientStatus(${status})")
    if (status.take(6) == "Error:") {
        log.error "Connection error..."
        sendEvent(name: "driverStatus", value: "ERROR")
        
        try {
            interfaces.mqtt.disconnect()  // clears buffers
        }
        catch (e) {
        }
        
        logDebug("Attempting to reconnect in 5 seconds...");
        runIn(5, connectToMqtt)
    }
    else {
        logDebug("Connected!")
        sendEvent(name: "driverStatus", value: "Connected")
    }
}


def connectToMqtt() {
    logDebug("connectToMqtt()")
    
    if (!interfaces.mqtt.isConnected()) {        
        logDebug("Connecting to MQTT...")
        interfaces.mqtt.connect("tcp://${flamebossServer}:1883", device.getDeviceNetworkId() + "driver", "T-" + username, authToken)
        
        runIn(1, subscribe)
    }
}


def subscribe() {
    logDebug("Subscribing...")
    
    // Subscribe to attributes
    interfaces.mqtt.subscribe(topicNameAttribute("open"))
    interfaces.mqtt.subscribe(topicNameAttribute("data"))
    interfaces.mqtt.subscribe(topicNameAttribute("rc"))
    interfaces.mqtt.subscribe(topicNameAttribute("console"))
    interfaces.mqtt.subscribe(topicNameAttribute("adc"))
}
            
def turnOffDevice() {
    off()   
    sendEvent(name: "pitTemp", value: "Device Offline", isStateChange: true)
    sendEvent(name: "probe1Temp", value: "Device Offline", isStateChange: true)
    sendEvent(name: "probe2Temp", value: "Device Offline", isStateChange: true)
    sendEvent(name: "probe3Temp", value: "Device Offline", isStateChange: true)
    sendEvent(name: "pitTempTarget", value: "Device Offline", isStateChange: true)
    sendEvent(name: "fanSpeed", value: "Device Offline", isStateChange: true)
    sendEvent(name: "meat1DoneTemp", value: "Device Offline", isStateChange: true)
    sendEvent(name: "meat1KeepWarmTemp", value: "Device Offline", isStateChange: true)
    sendEvent(name: "meat2DoneTemp", value: "Device Offline", isStateChange: true)
    sendEvent(name: "meat2KeepWarmTemp", value: "Device Offline", isStateChange: true)
    sendEvent(name: "meat3DoneTemp", value: "Device Offline", isStateChange: true)
    sendEvent(name: "meat3KeepWarmTemp", value: "Device Offline", isStateChange: true)
    sendEvent(name: "pitAlarmEnabled", value: "Device Offline", isStateChange: true)
    sendEvent(name: "pitAlarmTemp", value: "Device Offline", isStateChange: true)
    sendEvent(name: "contact", value: "Device Offline", isStateChange: true)
}

def turnOnDevice() {
    on()     
}


def topicNameCommand() {
    return "flameboss/${deviceID}/recv"   
}

def topicNameAttribute(String attribute) {      
    def topicBase = "flameboss/${deviceID}/send"    
    def topicName = "${topicBase}/${attribute}"
    return topicName
}

def on() {
    sendEvent(name: "switch", value: "on")
}

def off() {
    sendEvent(name: "switch", value: "off")
}


def refresh()
{
    initialize()
}

def logDebug(msg) 
{
    if (logEnable)
    {
        log.debug(msg)
    }
}  
