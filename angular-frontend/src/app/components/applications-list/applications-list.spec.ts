/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ApplicationsList } from './applications-list';

describe('ApplicationsList', () => {
  let component: ApplicationsList;
  let fixture: ComponentFixture<ApplicationsList>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ApplicationsList],
      providers: [provideRouter([])]
    })
    .compileComponents();

    fixture = TestBed.createComponent(ApplicationsList);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
