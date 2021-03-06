pipelineJob('oscc') {
  definition {
    cps {
      sandbox()
      script("""
node {
    // Set volume Name to \${BUILD_TAG} for each build gives new volume and therefore clean
    // environment. Use \${JOB_NAME} for incremental builds
    def volumeName='\${BUILD_TAG}'
    def analysis_image="\${DEFAULT_ANALYSIS_TAG}:\${DEFAULT_ANALYSIS_VERSION}"
	def idir_base='/opt/coverity/idirs'
	def idir=idir_base+'/idir'
	def config=idir_base+'/coverity_config.xml'

    try {
        stage('Clone sources') {
          deleteDir()    
          git url: 'https://github.com/PolySync/oscc.git'
          sh 'git checkout c08ae9982941842679b6b21847211c55d6db500c'
        }
        stage('Create helper image')
        {
            sh 'echo "FROM gcc:5.5.0" > Dockerfile'
            sh 'echo "RUN apt-get update && apt-get install -y arduino-core build-essential cmake" >> Dockerfile'
            docker.build("oscc-build:latest")
        }
        stage('Build (C++)') {
            docker.withRegistry('','docker_credentials') {  		
				docker.image(analysis_image).withRun('--hostname \${BUILD_TAG}  -v '+volumeName+':/opt/coverity') { c ->
					docker.image('oscc-build:latest').inside('--hostname \${BUILD_TAG}   -v '+volumeName+':/opt/coverity') { 
						sh 'cd firmware && rm -rf build && mkdir build && cd build && cmake .. -DKIA_SOUL=ON'
						sh '/opt/coverity/analysis/bin/cov-configure --config '+config+' --compiler avr-gcc --comptype gcc --template'
						sh '/opt/coverity/analysis/bin/cov-build --dir '+idir+' --emit-complementary-info   --config '+config+' make -j -C firmware/build'    
						try {
							sh '/opt/coverity/analysis/bin/cov-import-scm --dir '+idir+' --scm git --filename-regex \${WORKSPACE}'
						}
						catch (err)  { echo "cov-import-scm returned something unsavoury, moving on:"+err}												
					}            
				}
			}
        }
        stage('Analysis') {
            docker.image(analysis_image).inside('--hostname \${BUILD_TAG} --mac-address 08:00:27:ee:25:b2 -v '+volumeName+':/opt/coverity') {
                sh '/opt/coverity/analysis/bin/cov-analyze --dir '+idir+' --trial --misra-config /opt/coverity/analysis/config/MISRA/MISRA_cpp2008_7.config'
            }
        }
        stage('Commit') {
           withCoverityEnv(coverityToolName: 'default', connectInstance: 'Test Server') { 
                docker.image(analysis_image).inside('--network docker_coverity --hostname \${BUILD_TAG}  --mac-address 08:00:27:ee:25:b2 -v '+volumeName+':/opt/coverity -e HOME=/opt/coverity/idirs -w /opt/coverity/idirs -e COV_USER=\${COV_USER} -e COV_PASSWORD=\${COV_PASSWORD}') {
                    sh 'createProjectAndStream --host \${COVERITY_HOST} --user \${COV_USER} --password \${COV_PASSWORD} --project OSCC --stream oscc'
                    sh '/opt/coverity/analysis/bin/cov-commit-defects --dir '+idir+' --strip-path \${WORKSPACE} --host \${COVERITY_HOST} --port \${COVERITY_PORT} --stream oscc'
                }
            }
        }
    }
    catch (err){
      echo "Caught Exception: "+err
    }
    finally
    {
    stage('Cleanup volume') {
        // Comment out this to keep the idir volume
		sh 'docker volume rm '+volumeName
		}
    }
}
	  """.stripIndent())      
    }
  }
}
if (!jenkins.model.Jenkins.instance.getItemByFullName('oscc') && System.getenv("AUTO_RUN") ) {
 queue('oscc')
}
