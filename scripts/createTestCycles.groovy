import groovy.json.JsonSlurper
import java.text.SimpleDateFormat

properties([
        parameters([
                string(name: 'BUILD_NUMBER', defaultValue: '0.0.0', description: 'The build number you would like to create test cycles for'),
                booleanParam(name: 'TEST_FOLDER_1', defaultValue: false, description: 'The first test folder to ask if we would like to clone or not'),
                booleanParam(name: 'TEST_FOLDER_2', defaultValue: false, description: 'The second test folder to ask if we would like to clone or not'),
                // Put as many test folders as you would like here
        ])
])

// This script was made to run in Jenkins

pipeline {
  agent {label 'the agent you want this to run on'}
  stages {
        // Create the test cycles required for manual test cycle
        stage('Create Manual Zephyr Scale Test Cycles') {
            steps{
                script{
                // The template test cycles that we're going to clone
                    def testCycleTemplates = [:]

                    if (params.TEST_FOLDER_1){
                        testCycleTemplates = testCycleTemplates.plus(['Exact name of test folder 1':'Test folder ID for Zephyr Scale API to use'])
                    }
                    if (params.TEST_FOLDER_2){
                        testCycleTemplates = testCycleTemplates.plus(['Exact name of test folder 2':'Test folder ID for Zephyr Scale API to use'])
                    }
                    // add as many of these if blocks as you have test folders

                    println(testCycleTemplates)

                    env.ZEPHYR_PROJECT_KEY = "YOUR_JIRA_PROJECT" // Jira project key
                    def date = new Date()
                    def dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    env.ZEPHYR_DATE = "${dateFormat.format(date)}"
                    env.JIRA_PROJECT_VERSION = 10014 // junk initialized var

                    withCredentials([string(credentialsId: 'YOUR_JIRA_API_KEY', variable: 'YOUR_JIRA_API_KEY')]) {
                      def projectVersions = sh(script: "curl --request GET \
                            --url 'https:/your.jira.instance/rest/api/3/project/$env.ZEPHYR_PROJECT_KEY/versions' \
                            -u user@your_company.com:$YOUR_JIRA_API_KEY \
                            --header 'Accept: application/json'", returnStdout: true)

                      def json = readJSON text: projectVersions

                      json.any { version ->
                          if (version["name"] == env.TAG_NAME) {
                            env.JIRA_PROJECT_VERSION = version["id"]
                            true
                          }
                      }

                      sh "echo Project Version ID: $env.JIRA_PROJECT_VERSION"
                    }

                    withCredentials([string(credentialsId: 'ZEPHYR_SCALE_KEY', variable: 'ZEPHYR_SCALE_KEY')]) {
                      def testFolderName = "$BUILD_NUMBER - Test Cycle"
                      // Create a folder to hold our new test cycles
                      def folder = sh(script: "curl -X POST -d '{\"parentId\":null,\"name\": \"$testFolderName\",\"projectKey\": \"$env.ZEPHYR_PROJECT_KEY\",\"folderType\":\"TEST_CYCLE\",\"maxResults\":\"200\"}' \
                            -H \"Content-Type: application/json\" \
                            -H \"Authorization: Bearer $ZEPHYR_SCALE_KEY\" \
                            \"https://api.zephyrscale.smartbear.com/v2/folders\"", returnStdout: true)

                      def folderId = (new JsonSlurper().parseText(folder)).id

                      testCycleTemplates.each { cycleTemplate ->
                        def templateName = cycleTemplate.key
                        def templateKey = cycleTemplate.value
                        def cycleName = "$templateName - $BUILD_NUMBER"
                        println('Test template key:')
                        println(templateName)
                        println('Test template value:')
                        println(templateKey)
                        println('Test cycle name:')
                        println(cycleName)
                        def model = "Some_product" // Product model if you want to use it as a custom field

                        // Create a Test Cycle for each template
                        def testCycle = sh(script: "curl -X POST -d '{\"customFields\": {\"Model\":\"$model\"},\"plannedEndDate\":\"$env.ZEPHYR_DATE\",\"plannedStartDate\":\"$env.ZEPHYR_DATE\",\"jiraProjectVersion\": \"$env.JIRA_PROJECT_VERSION\",\"projectKey\": \"$env.ZEPHYR_PROJECT_KEY\",\"folderId\":$folderId,\"name\": \"$cycleName\"}' \
                              -H \"Content-Type: application/json\" \
                              -H \"Authorization: Bearer $ZEPHYR_SCALE_KEY\" \
                              \"https://api.zephyrscale.smartbear.com/v2/testcycles\"", returnStdout: true)

                        def cycleKey = (new JsonSlurper().parseText(testCycle)).key
                        sh "echo Test Cycle created: $cycleKey"

                        // Get the Test Executions from each template
                        def testExecutions = sh(script: "curl -X GET \
                              \"https://api.zephyrscale.smartbear.com/v2/testexecutions?projectKey=$env.ZEPHYR_PROJECT_KEY&testCycle=$templateKey&maxResults=200\" \
                              -H \"Authorization: Bearer $ZEPHYR_SCALE_KEY\"", returnStdout: true)

                        def json = readJSON text: testExecutions
                        def cases = json['values'].collect { it['testCase'] }

                        cases.each {
                          try {
                                def testCaseKey = (it['self'] =~ /YOUR_JIRA_PROJECT-T[\d]+/)[0] // Obv you'll have to make this regex work with your JIRA project name
                                println('testCaseKey:')
                                println(testCaseKey)

                                def testTestInfo = sh(script: "curl -X GET \
                                \"https://api.zephyrscale.smartbear.com/v2/testcases/$testCaseKey\" \
                                -H \"Authorization: Bearer $ZEPHYR_SCALE_KEY\"", returnStdout: true)
                                def jsonTest = readJSON text: testTestInfo
                                testCaseStatus = jsonTest['status']['id']
                                println(jsonTest)
                                println("Test case status key:")
                                println(testCaseStatus)

                                def testCaseStatusDetail = sh(script: "curl -X GET \
                                \"https://api.zephyrscale.smartbear.com/v2/statuses/$testCaseStatus\" \
                                -H \"Authorization: Bearer $ZEPHYR_SCALE_KEY\"", returnStdout: true)
                                def jsonStatus = readJSON text: testCaseStatusDetail
                                println(jsonStatus)
                                println("Test status:")
                                println(jsonStatus['name'])

                                if (jsonStatus['name'] == "Approved"){
                                    // Get the test case keys from each test execution
                                    // The test case key is in the format JIRA_PROJECT-T123, this extracts that from the url as the execution endpoint doesn't return it directly

                                    echo "Adding Test Case $testCaseKey to Test Cycle $cycleName ($cycleKey)"
                                    // Create a new 'Not Executed' test execution for each case from the template, in our new test cycle
                                    def createExecution = sh(script: "curl -X POST -d '{\"projectKey\": \"$env.ZEPHYR_PROJECT_KEY\", \"testCaseKey\": \"$testCaseKey\", \"testCycleKey\": \"$cycleKey\", \"statusName\": \"Not Executed\"}' \
                                      -H \"Content-Type: application/json\" \
                                      -H \"Authorization: Bearer $ZEPHYR_SCALE_KEY\" \
                                      \"https://api.zephyrscale.smartbear.com/v2/testexecutions\"", returnStdout: true)
                                }
                                else {
                                    println("Test was not added since status was not Approved")
                                }
                            }
                            catch (e) {
                                echo e.getMessage()
                            }
                        }
                      }
                    }
                }
            }
        }
    }
}