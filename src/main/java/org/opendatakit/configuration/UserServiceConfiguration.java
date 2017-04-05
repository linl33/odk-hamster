package org.opendatakit.configuration;

import java.beans.PropertyVetoException;

import org.opendatakit.common.persistence.Datastore;
import org.opendatakit.common.persistence.exception.ODKDatastoreException;
import org.opendatakit.common.security.Realm;
import org.opendatakit.common.security.UserService;
import org.opendatakit.common.security.spring.RoleHierarchyImpl;
import org.opendatakit.common.security.spring.UserServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.encoding.MessageDigestPasswordEncoder;
import org.springframework.security.authentication.encoding.ShaPasswordEncoder;

@Configuration
@Profile("default")
@ComponentScan(basePackages = {"org.opendatakit", "org.benetech"})
public class UserServiceConfiguration {
  
  @Autowired DataConfiguration dataConfiguration;


  @Value("${security.server.realm.realmString:opendatakit.org RC1 Aggregate 1.0 realm}")
  private String realmString;

  @Value("${security.server.hostname:localhost}")
  private String hostname;

  @Value("${security.server.port:8888}")
  private int port;

  @Value("${security.server.securePort:8443}")
  private int securePort;

  @Value("${security.server.channelType:ANY_CHANNEL}")
  private String channelType;

  @Value("${security.server.secureChannelType:ANY_CHANNEL}")
  private String secureChannelType;

  @Value("${external.root.url:http://localhost:8888}")
  private String changePasswordUrl;

  @Value("${security.server.superUserUsername:admin}")
  private String superUserUsername;

  @Value("")
  private String webApplicationBase;

  @Bean
  public Realm realm() {
    Realm realm = new Realm();
    realm.setRealmString(realmString);
    realm.setHostname(hostname);
    realm.setPort(port);
    realm.setSecurePort(securePort);
    realm.setChannelType(channelType);
    realm.setSecureChannelType(secureChannelType);
    realm.setChangePasswordURL(changePasswordUrl);
    return realm;
  }

  @Bean
  public UserService userService() throws ODKDatastoreException, PropertyVetoException {
    UserServiceImpl userServiceImpl = new UserServiceImpl();
    userServiceImpl.setRealm(realm());
    userServiceImpl.setDatastore(dataConfiguration.datastore());
    userServiceImpl.setSuperUserUsername(superUserUsername);
    return userServiceImpl;
  }

  
  @Bean 
  public MessageDigestPasswordEncoder passwordEncoder() {
    return new ShaPasswordEncoder();
  }
  
  @Bean
  public RoleHierarchyImpl hierarchicalRoleRelationships() throws ODKDatastoreException, PropertyVetoException {
    try {
      Class.forName("org.postgresql.Driver");
      //on classpath
    } catch(ClassNotFoundException e) {
      // not on classpath
    }
    RoleHierarchyImpl roleHierarchyImpl = new RoleHierarchyImpl();
    roleHierarchyImpl.setDatastore(dataConfiguration.datastore());
    roleHierarchyImpl.setUserService(userService());
    roleHierarchyImpl.setPasswordEncoder(passwordEncoder());
    return roleHierarchyImpl;
  }


}