pipeline {
    agent any

    environment {
        AWS_REGION = 'eu-west-2'
        AMI_ID = 'ami-09f708d8296de1099'          // Replace with valid AMI
        INSTANCE_TYPE = 't2.micro'
        KEY_NAME = 'testing'
        SECURITY_GROUP = 'sg-xxxxxxxx'
        INSTANCE_ID = ''
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

        stage('Build') {
            steps {
                sh 'mvn clean package'
            }
        }

        stage('Launch Test Instance') {
            steps {
                script {
                    INSTANCE_ID = sh(
                        script: """
                        aws ec2 run-instances \
                        --image-id ${AMI_ID} \
                        --instance-type ${INSTANCE_TYPE} \
                        --key-name ${KEY_NAME} \
                        --security-group-ids ${SECURITY_GROUP} \
                        --region ${AWS_REGION} \
                        --query 'Instances[0].InstanceId' \
                        --output text
                        """,
                        returnStdout: true
                    ).trim()

                    echo "Launched Instance: ${INSTANCE_ID}"

                    sh "aws ec2 wait instance-running --instance-ids ${INSTANCE_ID} --region ${AWS_REGION}"
                }
            }
        }

        stage('Deploy & Run Tests') {
            steps {
                script {
                    // Get public IP
                    def PUBLIC_IP = sh(
                        script: """
                        aws ec2 describe-instances \
                        --instance-ids ${INSTANCE_ID} \
                        --query 'Reservations[0].Instances[0].PublicIpAddress' \
                        --output text \
                        --region ${AWS_REGION}
                        """,
                        returnStdout: true
                    ).trim()

                    echo "Instance Public IP: ${PUBLIC_IP}"

                    // Copy build artifact
                    sh """
                    scp -o StrictHostKeyChecking=no target/*.jar ec2-user@${PUBLIC_IP}:/home/ec2-user/
                    """

                    // Run test commands remotely
                    sh """
                    ssh -o StrictHostKeyChecking=no ec2-user@${PUBLIC_IP} '
                        java -jar *.jar &
                        sleep 30
                        curl http://localhost:8080/actuator/health
                    '
                    """
                }
            }
        }
    }

    post {
        always {
            script {
                if (INSTANCE_ID?.trim()) {
                    sh """
                    aws ec2 terminate-instances \
                    --instance-ids ${INSTANCE_ID} \
                    --region ${AWS_REGION}
                    """

                    echo "Test instance terminated."
                }
            }
        }
    }
}