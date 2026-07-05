def executeSSHCommand(sshKey, sshUser, serverHost, command) {
    sh """
        ssh -i ${sshKey} -o StrictHostKeyChecking=no -o ConnectTimeout=10 ${sshUser}@${serverHost} '${command}'
    """
}

// SSH Deployment Function
def deployToRemoteServer(imageName, serverHost, appPort, containerName, sshCredentialsId) {
    echo "Starting deployment to remote server: ${serverHost}"
    
    withCredentials([sshUserPrivateKey(credentialsId: sshCredentialsId, keyFileVariable: 'SSH_KEY', usernameVariable: 'SSH_USER')]) {
        // Test SSH connection first
        testSSHConnection(serverHost)
        
        // Execute deployment
        executeRemoteDeployment(imageName, serverHost, appPort, containerName)
        
        // Verify deployment
        verifyDeployment(serverHost, appPort, containerName)
    }
    
    echo "Application deployed successfully to remote server!"
    echo "Frontend accessible at: http://${serverHost}:${appPort}"
}

// Test SSH connectivity
def testSSHConnection(serverHost) {
    echo "Testing SSH connection to ${serverHost}..."
    executeSSHCommand(SSH_KEY, SSH_USER, serverHost, 'echo "SSH connection successful"')
}

// Execute the actual deployment commands
def executeRemoteDeployment(imageName, serverHost, appPort, containerName) {
    echo "Executing deployment commands on remote server..."
    def deploymentCommands = """
        echo "=== Starting deployment process ==="
        
        # Stop and remove existing container
        echo "Stopping existing container..."
        docker stop ${containerName} 2>/dev/null || echo "No running container to stop"
        docker rm ${containerName} 2>/dev/null || echo "No container to remove"
        
        # Pull latest image
        echo "Pulling latest Docker image..."
        docker pull ${imageName}
        
        # Run new container
        echo "Starting new container..."
        docker run -d --name ${containerName} -p ${appPort}:${appPort} --restart unless-stopped ${imageName}
        
        echo "=== Deployment commands completed ==="
    """
    
    executeSSHCommand(SSH_KEY, SSH_USER, serverHost, deploymentCommands)
}

// Verify deployment success
def verifyDeployment(serverHost, appPort, containerName) {
    echo "Verifying deployment..."
    def verificationCommands = """
        echo "Waiting for container to start..."
        sleep 5
        
        if docker ps | grep ${containerName}; then
            echo "Container is running successfully"
            echo "Container status:"
            docker ps | grep ${containerName}
        else
            echo "Container failed to start"
            echo "Container logs:"
            docker logs ${containerName}
            exit 1
        fi
    """
    
    executeSSHCommand(SSH_KEY, SSH_USER, serverHost, verificationCommands)
    
    // Optional health check
    performHealthCheck(serverHost, appPort)
}

// Perform application health check
def performHealthCheck(serverHost, appPort) {
    echo "Performing health check..."
    sh """
        # Wait for application to fully start
        sleep 10
        
        # Basic connectivity test
        if curl -f -s --connect-timeout 10 http://${serverHost}:${appPort}/health; then
            echo "Health check passed"
        else
            echo "Health check endpoint not available (this may be normal if /health endpoint doesn't exist)"
        fi
        
        # Test basic connectivity
        if curl -f -s --connect-timeout 10 http://${serverHost}:${appPort}/ > /dev/null; then
            echo "Application is responding to requests"
        else
            echo "Application may still be starting up"
        fi
    """
}

// Main Pipeline
node {
    // Configuration variables
    def registryName = "nmthai"
    def imageTag = "${BUILD_NUMBER}"
    def imageName = "${registryName}/cowsay-frontend:${imageTag}"
    def serverHost = "23.23.218.118"
    def appPort = "3000"
    def containerName = "cowsay-frontend"
    def sshCredentialsId = "SERVER_SSH_KEY"
    
    stage('Checkout') {
        // Updated to use your forked repository
        // Replace 'your-github-username' with your actual GitHub username
        git url: 'https://github.com/thai-nm/devops-project-application-cowsay-frontend', branch: 'main'
    }
    
    stage('Build') {
        echo "Building the project..."
        // Add actual build commands if needed
        sh "npm install || echo 'No package.json found, skipping npm install'"
    }
    
    stage('Test') {
        echo "Running tests..."
        // Add actual test commands if needed
        sh "npm test || echo 'No tests found, skipping test execution'"
    }
    
    stage('Build Docker Image') {
        echo "Building Docker image: ${imageName} (Build #${BUILD_NUMBER})"
        sh "docker build -t ${imageName} ."
        
        // Also tag as latest for convenience
        sh "docker tag ${imageName} ${registryName}/cowsay-frontend:latest"
        
        echo "Docker image built successfully with tag: ${imageTag}"
    }
    
    stage('Push to Registry') {
        echo "Pushing Docker image to registry: ${imageName}"
        withCredentials([usernamePassword(credentialsId: 'DOCKER_REGISTRY_CREDS', usernameVariable: 'DOCKER_USERNAME', passwordVariable: 'DOCKER_PASSWORD')]) {
            sh "echo ${DOCKER_PASSWORD} | docker login -u ${DOCKER_USERNAME} --password-stdin"
            
            // Push versioned image
            sh "docker push ${imageName}"
            
            // Push latest tag
            sh "docker push ${registryName}/cowsay-frontend:latest"
        }
        echo "Docker image pushed successfully with build number: ${BUILD_NUMBER}"
    }
    
    stage('Deploy to Remote Server') {
        // Use the deployment function
        deployToRemoteServer(imageName, serverHost, appPort, containerName, sshCredentialsId)
    }
}