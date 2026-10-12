node('legacy') {
  sh 'echo started; sleep 60; echo "survived agent restart on $(uname -sr)"'
}
