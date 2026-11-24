/*
 * Job DSL for the Integration Catalog GUI tests.
 *
 *   integration-catalog-gui-tests - runs the Playwright GUI tests against a running catalog.
 *
 * Model B: parameters and the trigger live here, the pipeline is pure logic.
 */

pipelineJob('integration-catalog-gui-tests') {
    description('''\
Integration Catalog GUI tests. Playwright for Java drives a browser through the running catalog -
publishing and approving integration methods, editing their connectors, requests, API keys - with
the tests in gui-tests/ of Evolveum/integration-catalog, then publishes the JUnit results and, for a
failed test, its screenshot and Playwright trace. Runs nightly.''')
    logRotator {
        daysToKeep(-1)
        numToKeep(10)
        artifactDaysToKeep(-1)
        artifactNumToKeep(-1)
    }
    parameters {
        stringParam('BRANCH', 'main', 'integration-catalog branch whose gui-tests/ run.')
        stringParam('PLAYWRIGHT_IMAGE', 'mcr.microsoft.com/playwright/java:v1.64.0-noble', 'Test runner image; its Playwright version must match gui-tests/pom.xml.')
        stringParam('CATALOG_URL', 'http://localhost:8080', 'Where the browser opens the catalog.')
        stringParam('OPENPROJECT_URL', 'http://localhost:8090', 'The support portal the tests close review tickets in.')
        stringParam('TESTS', '', 'Surefire -Dtest filter, e.g. AdminFlowTest or !ApiKeyFlowTest; empty = all.')
        stringParam('VERBOSE', '0', 'Verbose output (1 = id/mvn --version).')
        booleanParam('CLEAN_WORKSPACE', false, 'Wipe the workspace before checkout.')
        booleanParam('CLEAN_M2_REPOSITORY', false, 'Clear the whole local Maven repo before testing.')
    }
    triggers {
        // Tests, nothing to poll for: nightly, after the day's integration-catalog-build images.
        cron('H H(3-5) * * *')
    }
    configure { project ->
        project / 'properties' << 'org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty' {
            abortPrevious(false)
        }
    }
    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url('https://gitlab.evolveum.com/infra/jenkins.git')
                        credentials('gitlab-infra-jenkins')
                    }
                    branch('master')
                }
            }
            scriptPath('pipelines/integration-catalog-gui-test-pipeline')
            lightweight(true)
        }
    }
}
