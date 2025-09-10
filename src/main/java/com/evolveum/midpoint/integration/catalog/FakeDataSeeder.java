package com.evolveum.midpoint.integration.catalog;

import com.evolveum.midpoint.integration.catalog.object.Application;
import com.evolveum.midpoint.integration.catalog.repository.*;

import com.github.javafaker.Faker;

import org.springframework.boot.CommandLineRunner;

/**
 * Created by Dominik.
 */
public class FakeDataSeeder implements CommandLineRunner {

    private final ApplicationRepository applicationRepository;
    private final ApplicationTagRepository applicationTagRepository;
    private final ConnidVersionRepository connidVersionRepository;
    private final CountryOfOriginRepository countryOfOriginRepository;
    private final ImplementationRepository implementationRepository;
    private final ImplementationTagRepository implementationTagRepository;
    private final ImplementationVersionRepository implementationVersionRepository;
    private final VotesRepository votesRepository;
    private final RequestRepository requestRepository;
    private final DownloadsRepository downloadsRepository;

    private final Faker faker = new Faker();

    public FakeDataSeeder(ApplicationRepository applicationRepository,
                          ApplicationTagRepository applicationTagRepository,
                          ConnidVersionRepository connidVersionRepository,
                          CountryOfOriginRepository countryOfOriginRepository,
                          ImplementationRepository implementationRepository,
                          ImplementationTagRepository implementationTagRepository,
                          ImplementationVersionRepository implementationVersionRepository, VotesRepository votesRepository, RequestRepository requestRepository,
                          DownloadsRepository downloadsRepository
    ) {
        this.applicationRepository = applicationRepository;
        this.applicationTagRepository = applicationTagRepository;
        this.connidVersionRepository = connidVersionRepository;
        this.countryOfOriginRepository = countryOfOriginRepository;
        this.implementationRepository = implementationRepository;
        this.implementationTagRepository = implementationTagRepository;
        this.implementationVersionRepository = implementationVersionRepository;
        this.votesRepository = votesRepository;
        this.requestRepository = requestRepository;
        this.downloadsRepository = downloadsRepository;
    }

    @Override
    public void run(String... args) {
//          TODO
//        if (applicationRepository.count() == 0) {  // only seed if empty
//            for (int i = 0; i < 20; i++) {
//                Application application = new Application()
//                        .build();
//
//                applicationRepository.save(application);
//            }
//            System.out.println("Generated 20 fake records!");
//        }
    }
}
