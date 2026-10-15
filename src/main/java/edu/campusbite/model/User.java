package edu.campusbite.model;

import java.io.Serializable;

public record User(long id, String name, String email, String role) implements Serializable {}
