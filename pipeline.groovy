pipeline {
      agent any

      environment {
            AWS_REGION     = "eu-west-2"
            AMI_ID         = "ami-09f708d8296de1099"
            INSTANCE_TYPE  = "t2.micro"
            KEY_NAME       = "Project2"
            SECURITY_GROUP = "sg-07d9d3a73b6bb865e" # (Security group ID . it must allow SSH access and port 8080)
         }

triggers {
        githubPush()   // Auto trigger on new Git commits
    }
         stages {

            stage('Checkout Code') {
                  steps {
                     git branch: 'main',
                        url: 'https://github.com/MeganathanU/testing.git'
                  }
            }

            stage('Build Binary') {
                  steps {
                     sh 'go build -o app main.go'
                  }
            }

            stage('Create EC2 Instance') {
                  steps {
                     withAWS(credentials: 'aws-creds', region: "${AWS_REGION}") {
                        script {

                              def instanceId = sh(
                                 script: """
                                    aws ec2 run-instances \
                                    --image-id ${AMI_ID} \
                                    --instance-type ${INSTANCE_TYPE} \
                                    --key-name ${KEY_NAME} \
                                    --security-group-ids ${SECURITY_GROUP} \
                                    --query 'Instances[0].InstanceId' \
                                    --output text
                                 """,
                                 returnStdout: true
                              ).trim()

                              env.INSTANCE_ID = instanceId
                              echo "Instance Created: ${instanceId}"

                              sh "aws ec2 wait instance-running --instance-ids ${instanceId}"

                              // Wait until status checks pass (important)
                              sh "aws ec2 wait instance-status-ok --instance-ids ${instanceId}"

                              def publicIp = sh(
                                 script: """
                                    aws ec2 describe-instances \
                                    --instance-ids ${instanceId} \
                                    --query 'Reservations[0].Instances[0].PublicIpAddress' \
                                    --output text
                                 """,
                                 returnStdout: true
                              ).trim()

                              env.PUBLIC_IP = publicIp
                              echo "Public IP: ${publicIp}"
                        }
                     }
                  }
            }

            stage('Deploy Binary') {
                  steps {
                     sshagent(['ec2-key']) {
                        sh """
                              echo "Copying binary..."
                              scp -o StrictHostKeyChecking=no app ubuntu@${PUBLIC_IP}:/home/ubuntu/

                              echo "Starting application..."
                              # We use nohup and redirect ALL streams to ensure SSH can exit
                              ssh -o StrictHostKeyChecking=no ubuntu@${PUBLIC_IP} "
                                 chmod +x /home/ubuntu/app
                                 nohup /home/ubuntu/app > /home/ubuntu/app.log 2>&1 & 
                                 sleep 1 # Give it a second to initialize
                                 exit
                              "
                              echo "SSH session closed, proceeding to next stage."
                        """
                     }
                  }
            }

            stage('Test Application') {
                  steps {
                     sh """
                        echo "Waiting for app to start..."
                        sleep 20
                        curl --retry 5 --retry-delay 5 http://${PUBLIC_IP}:8080
                     """
                  }
            }

            stage('Wait 2 Minutes') {
                  steps {
                     sh "sleep 120"
                  }
            }

         post {
            always {
                  script {
                     if (env.INSTANCE_ID) {
                        withAWS(credentials: 'aws-creds', region: "${AWS_REGION}") {
                              sh "aws ec2 terminate-instances --instance-ids ${INSTANCE_ID}"
                              echo "EC2 Terminated."
                        }
                     }
                  }
                  echo "Pipeline Completed Successfully."
            }
         }
      }