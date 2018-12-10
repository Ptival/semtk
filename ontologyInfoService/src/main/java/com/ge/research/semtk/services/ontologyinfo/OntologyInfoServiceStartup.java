package com.ge.research.semtk.services.ontologyinfo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import com.ge.research.semtk.auth.AuthorizationManager;

@Component
public class OntologyInfoServiceStartup implements ApplicationListener<ApplicationReadyEvent> {
	@Autowired
	private OntologyInfoAuthProperties auth_prop;
	
	@Override
	public void onApplicationEvent(final ApplicationReadyEvent event) {
		
		try {
			AuthorizationManager.authorize(auth_prop);
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
	}		
}