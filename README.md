# Groovy
Sample Groovy code. Note: not runnable

This code snippet illustrates how unit testing is incorporated into the project. Code snippet instantiates a cron job that invokes a service to perform certain tasks.

MaintenanceCurationTasksCronJob.groovy : the class that initates the cron job and invokes the service

CurationTaskService.groovy : peforms a set of tasks at the time specified in the cron job

CurationTastServiceUnitSpec.groovy : unit tests for the above service using the Spock framework
