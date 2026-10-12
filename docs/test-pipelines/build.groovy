node('legacy') {
  stage('Where does sh run?') {
    sh 'uname -a; cat /etc/redhat-release 2>/dev/null || oslevel -s 2>/dev/null; java -version 2>&1 || echo "no Java on this host"'
  }
  stage('Build') {
    writeFile file: 'src/version.txt', text: "1.2.3\n"
    sh '''
      mkdir -p out
      echo "built $(cat src/version.txt) on $(uname -sr)" > out/result.txt
      printf '<testsuite name="legacy" tests="1" failures="0"><testcase classname="legacy" name="os_%s"/></testsuite>\n' "$(uname -s)" > out/TEST-legacy.xml
    '''
    junit 'out/TEST-*.xml'
    archiveArtifacts 'out/result.txt'
    stash name: 'out', includes: 'out/**'
    echo "returnStdout: ${sh(script: 'uname -sr', returnStdout: true).trim()}"
    echo "returnStatus: ${sh(script: 'exit 3', returnStatus: true)}"
    withEnv(['GREETING=it\'s from the controller']) { sh 'echo "GREETING=$GREETING"' }
  }
}
node('linux')  // any other agent {
  unstash 'out'
  sh 'echo "unstashed on controller: $(cat out/result.txt)"'
}
