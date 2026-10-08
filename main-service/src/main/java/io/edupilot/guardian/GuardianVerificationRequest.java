package io.edupilot.guardian;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import io.edupilot.user.User;

/** Intake metadata only. No guardian identity, evidence, approved actor, or inferred consent. */
@Entity
@Table(name="guardian_verification_requests")
public class GuardianVerificationRequest {
 @Id @Column(length=36) private String id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="user_id",nullable=false,unique=true) private User user;
 @Column(nullable=false,length=30) private String provider;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private Status status;
 @Column(name="requested_at",nullable=false) private Instant requestedAt;
 protected GuardianVerificationRequest() {}
 static GuardianVerificationRequest manual(User user,Instant now) {
  var request=new GuardianVerificationRequest();request.id=UUID.randomUUID().toString();request.user=user;
  request.provider="manual";request.status=Status.PENDING;request.requestedAt=now;return request;
 }
 public String getId(){return id;}public String getProvider(){return provider;}public Status getStatus(){return status;}
 public Instant getRequestedAt(){return requestedAt;}
 public Long getUserId(){return user.getId();}
 public enum Status { PENDING, CANCELLED }
}
