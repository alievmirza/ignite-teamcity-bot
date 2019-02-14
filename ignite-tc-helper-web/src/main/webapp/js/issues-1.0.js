/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
//@param results - TestFailuresSummary
//@param settings - Settings (JS class)
function showIssues(result) {
    var res = "";
    res += "Build problems";
    res += "<br>";

    if (!isDefinedAndFilled(result.issues)) {
        return res;
    }

    for (var i = 0; i < result.issues.length; i++) {
        var issue = result.issues[i];

        var color = 'red';
        var issueTitle = '';
        res += " <span style='border-color: " + color + "; width:6px; height:6px; display: inline-block; border-width: 4px; color: black; border-style: solid;' title='" + issueTitle + "'></span> ";

        res += issue.displayType;

        res += " " + issue.issueKey.testOrBuildName;

        if (isDefinedAndFilled(issue.addressNotified)) {
            res += " Users Notified: [";

            for (var j = 0; j < issue.addressNotified.length; j++) {
                var addressNotified = issue.addressNotified[j];

                res += addressNotified + ", "
            }
            res += "]";
        }

        res += "<br><br>";
    }

    return res;
}