# Use a lightweight base image with Go preinstalled
FROM golang:1.23-alpine

# Set the working directory inside the container
WORKDIR /app

# Copy all files from the current directory to the container
COPY ./pgstream .

# Install necessary dependencies (if any additional tools are required)
RUN apk add --no-cache bash

# Build the Go application
RUN go build