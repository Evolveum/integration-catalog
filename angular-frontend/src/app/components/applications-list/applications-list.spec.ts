import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ApplicationsList } from './applications-list';

describe('ApplicationsList', () => {
  let component: ApplicationsList;
  let fixture: ComponentFixture<ApplicationsList>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ApplicationsList]
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
