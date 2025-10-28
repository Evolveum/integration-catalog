import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ApplicationDetail } from './application-detail';

describe('ApplicationDetail', () => {
  let component: ApplicationDetail;
  let fixture: ComponentFixture<ApplicationDetail>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ApplicationDetail]
    })
    .compileComponents();

    fixture = TestBed.createComponent(ApplicationDetail);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
