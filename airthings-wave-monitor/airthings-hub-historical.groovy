/**
 *  Virtual Air Quality Monitor
 *
 *  Collects cloud-based data from Airthings Wave Plus for T, H, P, CO2, TVOC and
 *  Radon values.
 *
 *  Requires username and password for Airthings web/mobile login, as well as the
 *  device ID. This can be retrieved from the URL when accessing the 'wave' device
 *  from a web browser.
 *
 *  Does not support real-time statistics, as it uses the CSV export functionality.
 *  Every 'fetchData' call will retrieve the latest data, excluding duplicates, and
 *  will only store the most recent data points (maxDataPoints controls the count).
 *
 *  Driver provides native support for 'back-off' functionality when downloading CSV
 *  file, since large files can take some time to generate and become available.
 *
 *  Original driver "Copyright 2020 LostJen". Updated the driver to support Airthings Wave
 *  cloud-based reporting.
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * ------------------------------------------------------------------------------------------------------------------------------
 *
 */
metadata {
	definition (name: "Airthings Hub Historical Air Quality Monitor", namespace: "com.joeyorlando.hubitat.airthings", author: "joeyx22lm", importUrl: "https://raw.githubusercontent.com/joeyx22lm/hubitat-extensions/master/airthings-wave-monitor/airthings-hub-historical.groovy") {
      capability "Sensor"
      capability "Temperature Measurement"
	    capability "Relative Humidity Measurement"
      capability "Pressure Measurement"
      capability "Carbon Dioxide Measurement"

      attribute "temperature", "number"
      attribute "humidity", "number"
      attribute "pressure", "number"
      attribute "carbonDioxide", "number"
      attribute "tVOC", "number"
      attribute "radonShortTermAvg", "number"
      attribute "lastUpdate", "date"
      attribute "totalDataPoints", "number"

	    preferences {
          section("Airthings Cloud Settings:") {
	           input name: "username", type: "string", title: "Airthings Cloud Username", required: true, displayDuringSetup: true, defaultValue: null
	           input name: "password", type: "string", title: "Airthings Cloud Password", required: true, displayDuringSetup: true, defaultValue: null
 	           input name: "deviceId", type: "string", title: "Airthings Device ID", required: true, displayDuringSetup: true, defaultValue: null
          }
          section("Airthings Import Settings:") {
	           input name: "maxDataPoints", type: "number", title: "Maximum number of data points to store", required: true, displayDuringSetup: true, defaultValue: 30
          }
	    }

      command "fetchData", null
	}
}

def fetchData() {
    log.debug("Attempting to fetch AirThings data via CSV download -- last pull date: ${device.currentValue("lastUpdate")}")
    doLogin()
}

// Step #1: Login with username and password, receive bearer token
def doLogin() {
    try
    {
        def postParams = [
            uri: "https://accounts-api.airthings.com/v1/token",
            requestContentType: "application/json",
            contentType: 'application/json',
            body: [
                username: username,
                password: password,
                "grant_type": "password",
                "client_id": "accounts"
            ],
            timeout: 60
        ]
        asynchttpPost('handleLogin', postParams)
    }
    catch(Exception e)
    {
        if(e.message.toString() != "OK")
        {
            log.error("Unable to login as username: ${username} - ${e.message}")
        }
    }
}

def handleLogin(response, data) {
    if(response.hasError())
    {
        log.error("response.error = ${response.getErrorMessage()}")
        return;
    }
    if(response.getStatus() != 200) {
        log.error("Unexpected HTTP Status Code: ${response.getStatus()}")
        return;
    }
    def body = response.getJson()
    if(!body.containsKey("access_token")) {
        log.error("HTTP response did not contain 'access_token': ${body}")
        return;
    }
    log.debug("Logged in successfully as username: ${username}")
    doOAuthAuthorization(body.get("access_token").trim())
}

// Step #2: Exchange bearer token for OAuth authorization code
def doOAuthAuthorization(bearerToken) {
    try
    {
        def postParams = [
            uri: "https://accounts-api.airthings.com/v1/authorize?client_id=dashboard&redirect_uri=https%3A%2F%2Fdashboard.airthings.com",
            requestContentType: "application/json",
            contentType: 'application/json',
            headers: [
                authorization: "Bearer " + bearerToken,
            ],
            body: [
                "scope": [
                    "dashboard"
                ]
            ],
            timeout: 60
        ]
        asynchttpPost('handleOAuthAuthorization', postParams)
    }
    catch(Exception e)
    {
        if(e.message.toString() != "OK")
        {
            log.error("Unable to POST to OAuthAuthorization with token ${bearerToken} - ${e.message}")
        }
    }
}

def handleOAuthAuthorization(response, data) {
    if(response.hasError())
    {
        log.error("OAuth authorization response.error = ${response.getErrorMessage()}")
        return;
    }
    if(response.getStatus() != 200) {
        log.error("OAuth authorization Unexpected HTTP Status Code: ${response.getStatus()}")
        return;
    }
    def body = response.getJson()
    if(!body.containsKey("redirect_uri")) {
        log.error("OAuth authorization HTTP response did not contain 'redirect_uri': ${body}")
        return;
    }
    try {
        def authCode = body.get("redirect_uri").split("code=")[1];
        log.debug("Successfully obtained OAuth authorization code")
        doOAuthAuthentication(authCode)
    } catch (Exception e) {
        log.error("Unable to extract OAuth authorization code from body: ${body} - ${e.message}")
    }
}

// Step #3: Exchange OAuth authorization code for OAuth access token
def doOAuthAuthentication(authCode) {
    try
    {
        def postParams = [
            uri: "https://accounts-api.airthings.com/v1/token",
            requestContentType: "application/json",
            contentType: 'application/json',
            body: [
                "grant_type": "authorization_code",
                "client_id": "dashboard",
                "client_secret": "e333140d-4a85-4e3e-8cf2-bd0a6c710aaa",
                "code": authCode,
                "redirect_uri": "https://dashboard.airthings.com"
            ],
            timeout: 60
        ]
        asynchttpPost('handleOAuthAuthentication', postParams)
    }
    catch(Exception e)
    {
        if(e.message.toString() != "OK")
        {
            log.error("Unable to POST to OAuth authentication with authCode ${authCode} - ${e.message}")
        }
    }
}

def handleOAuthAuthentication(response, data) {
    if(response.hasError())
    {
        log.error("OAuth Authentication response.error = ${response.getErrorMessage()}")
        return;
    }
    if(response.getStatus() != 200) {
        log.error("OAuth Authentication Unexpected HTTP Status Code: ${response.getStatus()}")
        return;
    }
    def body = response.getJson()
    if(!body.containsKey("access_token")) {
        log.error("OAuth Authentication HTTP response did not contain 'access_token': ${body}")
        return;
    }
    try {
        def accessToken = body.get("access_token").trim();
        log.debug("Successfully obtained OAuth authentication token")
        fetchCSVDownloadUrl(accessToken)
    } catch (Exception e) {
        log.error("Unable to extract OAuth Authentication token from body: ${body}")
    }
}

// Step #4: Request CSV generation and download URL using OAuth access token
def fetchCSVDownloadUrl(access_token) {
    try
    {
        def getParams = [
            uri: "https://web-api.airthin.gs/v1/devices/${deviceId}/segments/latest/csv",
            headers: [
                authorization: access_token,
            ],
            contentType: 'application/json',
            timeout: 60
        ]
        asynchttpGet('handleCSVDownloadUrlResponse', getParams)
    }
    catch(Exception e)
    {
        if(e.message.toString() != "OK")
        {
            log.error("Unable to retrieve CSV download URL for deviceId: ${deviceId} - ${e.message}")
        }
    }
}

def handleCSVDownloadUrlResponse(response, data){
    if(response.hasError())
    {
        log.error("response.error = ${response.getErrorMessage()}")
        return;
    }
    if(response.getStatus() != 200) {
        log.error("Unexpected HTTP Status Code: ${response.getStatus()}")
        return;
    }
    def body = response.getJson()
    if(!body.containsKey("url")) {
        log.error("HTTP response did not contain 'url': ${body}")
        return;
    }
    log.debug("Retrieving CSV data file for deviceId: ${deviceId}")
    runIn(0, "fetchCSVFile", [data: [csvDownloadUrl: body.url, tryCount: 1]])
}

// Step #5: Download CSV file and parse contents
def fetchCSVFile(data) {
    try
    {
        if (data.tryCount > 2) {
            log.error("Unable to download CSV data file for deviceId: ${deviceId} from URL: ${data.csvDownloadUrl} after ${data.tryCount * 10} seconds")
            return
        }
        String[] downloadUrlURISplit = data.csvDownloadUrl.split("\\.com")
        String[] downloadUrlQueryStringSplit = downloadUrlURISplit[1].split("\\.csv\\?")
        asynchttpGet('handleCSVFileResponse', [
            uri: downloadUrlURISplit[0] + ".com",
            path: downloadUrlQueryStringSplit[0] + ".csv",
            queryString: downloadUrlQueryStringSplit[1],
            timeout: 60
        ], [csvDownloadUrl: data.csvDownloadUrl, tryCount: data.tryCount])
    }
    catch(Exception e)
    {
        if(e.message.toString() != "OK")
        {
            log.error("Unable to download CSV data file for deviceId: ${deviceId} - ${e.message}")
        }
    }
}

def handleCSVFileResponse(response, data) {
    if(response.hasError())
    {
        log.error("response.error = ${response.getErrorMessage()}")
        return;
    }
    if(response.getStatus() != 200) {
        log.error("Unexpected HTTP Status Code: ${response.getStatus()}")
        return;
    }

    def csvFileBase64 = response.getData();
    def csvFile = new String(csvFileBase64.bytes.decodeBase64())
    String[] csvFileLines = csvFile.split("\n");

    log.debug("Parsing CSV with ${csvFileLines.length} data points for deviceId: ${deviceId}")
    if (csvFileLines.length <= 1) {
        log.warn("Invalid CSV file was retrieved. Attempting to retry download for deviceId: ${deviceId}")
        runIn(10, "fetchCSVFile", [data: [csvDownloadUrl: data.csvDownloadUrl, tryCount: data.tryCount+1]])
        return
    }

    def dataValues = []
    def maxIndex = 0;
    String headerRow = csvFileLines[0];
    def headerMap = [:]
    headerRow.split(';').eachWithIndex { cell, cellIndex ->
        log.debug("Found header: ${cell.trim()} for index ${cellIndex}")
        headerMap[cell.trim()] = cellIndex
        if (cellIndex > maxIndex) {
            maxIndex = cellIndex
        }
    }

    def recordedDateIndex = headerMap.get("recorded");
    def radonShortTermAvgIndex = headerMap.get("RADON_SHORT_TERM_AVG pCi/L");
    def tempIndex = headerMap.get("TEMP °F");
    def humidityIndex = headerMap.get("HUMIDITY %");
    def pressureIndex = headerMap.get("PRESSURE mBar");
    def co2Index = headerMap.get("CO2 ppm");
    def vocIndex = headerMap.get("VOC ppb");

    def mostRecentData = null;
    if (device.currentValue("lastUpdate") != null) {
        mostRecentData = new Date(device.currentValue("lastUpdate"))
    }

    Integer newDataPoints = 0;
    for (Integer i = csvFileLines.length - 1; i > 0; i--) {
        if (newDataPoints >= maxDataPoints) {
            log.error("Skipping ingestion for any additional data points as the maximum has been reached for deviceId: ${deviceId}")
            break;
        }

        String row = csvFileLines[i];
        String[] cells = row.split(';');
        if (maxIndex > cells.length) {
            log.error("Unable to handle CSV row with unexpected cell count on line ${i}: ${row}")
            return;
        }

        if ((i % 10) == 0) {
            log.debug("Processed ${(csvFileLines.length - (i + 1))} records")
        }

        def rowDate = Date.parse("yyyy-MM-dd'T'HH:mm:ss", cells[recordedDateIndex])
        if (mostRecentData == null || mostRecentData.compareTo(rowDate) >= 0) {
            sendEvent(name: "temperature", value: Double.valueOf(cells[tempIndex]).round(2), unit: "°", isStateChange: true)
            sendEvent(name: "humidity", value: Double.valueOf(cells[humidityIndex]).round(0), unit: "%", isStateChange: true)
            sendEvent(name: "pressure", value: Double.valueOf(cells[pressureIndex]).round(0), unit: "mbar", isStateChange: true)
            sendEvent(name: "carbonDioxide", value: Double.valueOf(cells[co2Index]).round(0), unit: "ppm", isStateChange: true)
            sendEvent(name: "tVOC", value: Double.valueOf(cells[vocIndex]).round(0), unit: "ppb", isStateChange: true)
            sendEvent(name: "radonShortTermAvg", value: Double.valueOf(cells[radonShortTermAvgIndex]).round(0), unit: "pCi/L", isStateChange: true)
            sendEvent(name: "lastUpdate", value: rowDate, isStateChange: true)
            newDataPoints++
        } else {
            log.debug("Reached chronological data point that already exists for deviceId: ${deviceId}. Stopping further ingestion.")
            break
        }
    }
    sendEvent(name: "totalDataPoints", value: totalDataPoints, isStateChange: true)
    log.info("Added ${newDataPoints} new data points for deviceId: ${deviceId}")
}
