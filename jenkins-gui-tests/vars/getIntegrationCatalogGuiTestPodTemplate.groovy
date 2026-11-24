/*
 * Kubernetes pod template for the Integration Catalog GUI test pipeline.
 *
 * jnlp + playwright (Maven, JDK and the browsers the Playwright Java tests drive).
 *
 * TODO (infra): the test environment - the catalog itself on localhost:8080 (the image built by
 * integration-catalog-build), its PostgreSQL, Keycloak with the integration-catalog realm on
 * localhost:8081, OpenProject on localhost:8090 and, for ApiKeyFlowTest, Gravitee's management API.
 * Containers of a pod share localhost, which is what the tests need: the browser and the catalog
 * must reach Keycloak by the same URL, and the catalog's secure cookies only work over plain HTTP
 * on localhost. The application side's working reference is gui-tests/compose.yml in
 * Evolveum/integration-catalog (commit 436c477), see docs/integration-catalog-gui-tests.adoc.
 *
 * Config keys:
 *   playwrightImage (optional) - default mcr.microsoft.com/playwright/java:v1.64.0-noble,
 *                                must match the Playwright version in gui-tests/pom.xml
 *   runnerMemory    (optional) - default 6Gi
 *   runnerCpu       (optional) - default 3
 */
def call(Map config = [:]) {
    def playwrightImage = config.playwrightImage ?: 'mcr.microsoft.com/playwright/java:v1.64.0-noble'
    def runnerMemory    = config.runnerMemory ?: '6Gi'
    def runnerCpu       = config.runnerCpu ?: '3'

    return """
apiVersion: v1
kind: Pod
metadata:
  namespace: jenkins-builds
  labels:
    app: integration-catalog-gui-tests
  annotations:
    k8s-ttl-controller.twin.sh/ttl: "12h"
spec:
  serviceAccountName: jenkins-test-runner
  terminationGracePeriodSeconds: 30
  containers:
  - name: jnlp
    image: jenkins/inbound-agent:3327.v868139a_d00e0-6-jdk21
    resources:
      requests:
        memory: "512Mi"
        cpu: "200m"
        ephemeral-storage: "1Gi"
      limits:
        memory: "1Gi"
        cpu: "2"
        ephemeral-storage: "4Gi"
  - name: playwright
    image: ${playwrightImage}
    command:
    - cat
    tty: true
    env:
    # The image already carries the browsers; never download them again behind the proxy.
    - name: PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD
      value: "1"
    resources:
      requests:
        memory: "2Gi"
        cpu: "1"
        ephemeral-storage: "2Gi"
      limits:
        memory: "${runnerMemory}"
        cpu: "${runnerCpu}"
        ephemeral-storage: "8Gi"
    volumeMounts:
    - name: m2-cache
      mountPath: /m2cache
    # Chromium crashes on the default 64 MB /dev/shm.
    - name: dshm
      mountPath: /dev/shm
  volumes:
  - name: m2-cache
    persistentVolumeClaim:
      claimName: jenkins-m2-cache
  - name: dshm
    emptyDir: {medium: Memory, sizeLimit: 2Gi}
"""
}
