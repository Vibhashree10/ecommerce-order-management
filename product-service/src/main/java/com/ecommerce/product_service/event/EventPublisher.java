package com.ecommerce.product_service.event;
/*
* Design Patterns achieved through this :
* 1. Publisher-Subscriber (Pub/Sub) Pattern (Architectural Pattern)
At a high-level microservices and system design level, this is a textbook
* implementation of the Pub/Sub pattern. The EventPublisher acts as the
* publishing abstraction that broadcasts messages/events without needing to
* know who the downstream consumers or subscribers are, achieving loose
* coupling between services or components.
*
* 2. Observer Pattern (Object-Oriented Design Pattern)
From a pure GoF (Gang of Four) object-oriented perspective, this structure
* mirrors the Observer Pattern:
Subject/Publisher: The component utilizing EventPublisher to trigger updates.
Observer/Listener: The downstream handlers that react when publish() is
* invoked.
*
* Strategy Pattern (Behavioral Design Pattern)
By coding against the EventPublisher interface rather than a concrete
* implementation class, you are also applying the Strategy Pattern
* (or polymorphism). This allows you to swap out how events are dispatched
* for example, switching from a synchronous local execution implementation
* to an asynchronous one backed by Kafka, RabbitMQ, or AWS SNS/SQS—without
* changing any of the business logic code that calls the publish method.
* */
public interface EventPublisher {
    void publish(String topic, String message);
}
