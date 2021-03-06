package br.org.cidadessustentaveis.services;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.apache.commons.mail.EmailException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.org.cidadessustentaveis.dto.AprovacaoPrefeituraDTO;
import br.org.cidadessustentaveis.dto.AprovacaoPrefeituraFiltroDTO;
import br.org.cidadessustentaveis.dto.AprovacaoPrefeituraPendenteDTO;
import br.org.cidadessustentaveis.dto.AprovacaoPrefeituraSimplesDTO;
import br.org.cidadessustentaveis.model.administracao.AprovacaoPrefeitura;
import br.org.cidadessustentaveis.model.administracao.Cidade;
import br.org.cidadessustentaveis.model.administracao.EmailToken;
import br.org.cidadessustentaveis.model.administracao.Prefeitura;
import br.org.cidadessustentaveis.model.enums.FuncionalidadeToken;
import br.org.cidadessustentaveis.repository.AprovacaoPrefeituraRepository;
import br.org.cidadessustentaveis.services.exceptions.ObjectNotFoundException;
import br.org.cidadessustentaveis.util.EmailUtil;
import br.org.cidadessustentaveis.util.ProfileUtil;
import br.org.cidadessustentaveis.util.SenhaUtil;

@Service
public class AprovacaoPrefeituraService {

	@Autowired
	private AprovacaoPrefeituraRepository repository;
	@Autowired
	private ProfileUtil profileUtil;
	@Autowired
	private EmailUtil emailUtil;
	@Autowired
	private EmailTokenService emailTokenService;
	@Autowired
	private PrefeituraService prefeituraService;
	@Autowired
	private EntityManager em;
	@Autowired
	private CidadeService cidadeService;

	public AprovacaoPrefeitura criarPedidoAprovacao(Prefeitura prefeitura) {
		AprovacaoPrefeitura aprovacaoPrefeitura = AprovacaoPrefeitura.builder().prefeitura(prefeitura).data(new Date())
				.status("Pendente").build();
		AprovacaoPrefeitura entity = repository.save(aprovacaoPrefeitura);
		return entity;
	}

	public AprovacaoPrefeitura aprovar(AprovacaoPrefeituraSimplesDTO aprovacao) {
		AprovacaoPrefeitura aprovacaoPrefeitura = alterarStatus(aprovacao.getId(), "Aprovada", null);
		prefeituraService.alterarDataMandato(aprovacaoPrefeitura.getPrefeitura(), aprovacao.getInicioMandato(),
				aprovacao.getFimMandato());
		EmailToken emailToken = EmailToken.builder().ativo(Boolean.TRUE)
				.funcionalidadeToken(FuncionalidadeToken.APROVACAO_PREFEITURA)
				.hash(SenhaUtil.criptografarSHA2(aprovacaoPrefeitura.getId() + ""
						+ aprovacaoPrefeitura.getPrefeitura().getNome() + "" + LocalDateTime.now().getNano()))
				.aprovacaoPrefeitura(aprovacaoPrefeitura).build();
		emailTokenService.salvar(emailToken);
		try {
			emailAprovacaoPrefeituraV2(emailToken);
		} catch (EmailException e) {
			e.printStackTrace();
		}

		return aprovacaoPrefeitura;
	}
	
	@Transactional
	public AprovacaoPrefeitura aprovarAlterarDados(AprovacaoPrefeituraPendenteDTO aprovacao) {
		AprovacaoPrefeitura aprovacaoPrefeitura = alterarStatus(aprovacao.getId(), "Aprovada", null);
		Prefeitura prefeitura = prefeituraService.buscarPorId(aprovacao.getPrefeitura().getId());
		aprovacao.getPrefeitura().setPartidoPolitico(prefeitura.getPartidoPolitico());
		aprovacao.getPrefeitura().setCidade(prefeitura.getCidade());
		prefeituraService.alterarDataMandato(aprovacao.getPrefeitura(), aprovacao.getInicioMandato(),
				aprovacao.getFimMandato());
		EmailToken emailToken = EmailToken.builder().ativo(Boolean.TRUE)
				.funcionalidadeToken(FuncionalidadeToken.APROVACAO_PREFEITURA)
				.hash(SenhaUtil.criptografarSHA2(aprovacaoPrefeitura.getId() + ""
						+ aprovacaoPrefeitura.getPrefeitura().getNome() + "" + LocalDateTime.now().getNano()))
				.aprovacaoPrefeitura(aprovacaoPrefeitura).build();
		emailTokenService.salvar(emailToken);
		try {
			emailAprovacaoPrefeituraV2(emailToken);
		} catch (EmailException e) {
			e.printStackTrace();
		}

		return aprovacaoPrefeitura;
	}

	public AprovacaoPrefeitura reprovar(Long idAprovacaoPrefeitura, String justificativa) {
		AprovacaoPrefeitura aprovacaoPrefeitura = alterarStatus(idAprovacaoPrefeitura, "Reprovada", justificativa);
		try {
			emailReprovacaoPrefeitura(aprovacaoPrefeitura, justificativa);
		} catch (EmailException ex) {
			ex.printStackTrace();
		}
		return aprovacaoPrefeitura;
	}

	public boolean reenviarEmailPrefeitura(Long idPrefeitura, String listaEmail) {
		Prefeitura prefeitura = prefeituraService.buscarPorId(idPrefeitura);
		AprovacaoPrefeitura pedido = repository.findByPrefeituraAndStatus(prefeitura, "Aprovada");
		if (pedido != null) {
			pedido.getPrefeitura().setEmail(listaEmail);
			EmailToken emailToken = emailTokenService.reenviarEmailPrefeitura(pedido.getPrefeitura(),
					FuncionalidadeToken.APROVACAO_PREFEITURA, true);
			if (emailToken != null) {
				emailTokenService.salvar(emailToken);
				try {
					emailAprovacaoPrefeitura(emailToken);
					return true;
				} catch (EmailException e) {
					e.printStackTrace();
				}
			}

		}
		return false;
	}

	public List<AprovacaoPrefeitura> getAprovacoesPrefeituras() {
		return repository.findAllByOrderByDataDesc();
	}
	
	public List<AprovacaoPrefeituraDTO> filtrarAprovacaoPrefeitura(AprovacaoPrefeituraFiltroDTO aprovacaoPrefeituraFiltroDTO) {
		
		CriteriaBuilder cb = em.getCriteriaBuilder();

		CriteriaQuery<AprovacaoPrefeituraDTO> query = cb.createQuery(AprovacaoPrefeituraDTO.class);
		
		Root<AprovacaoPrefeitura> aprovacaoPrefeitura = query.from(AprovacaoPrefeitura.class);
		
		Join<AprovacaoPrefeitura, Prefeitura> joinPrefeitura = aprovacaoPrefeitura.join("prefeitura",JoinType.LEFT);
		Join<Prefeitura, Cidade> joinCidade = joinPrefeitura.join("cidade",JoinType.LEFT);
		
		
		query.multiselect(aprovacaoPrefeitura);
		
		List<javax.persistence.criteria.Predicate> predicateList = new ArrayList<>();
		
		DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy-MM-dd");	
		
		if (aprovacaoPrefeituraFiltroDTO.getNomePrefeitura() != null && !aprovacaoPrefeituraFiltroDTO.getNomePrefeitura().equals("")) {
			Path<String> nomePrefeitura = joinCidade.get("nome");
			Predicate predicateNomePrefeitura = cb.like(cb.lower(nomePrefeitura), "%" + aprovacaoPrefeituraFiltroDTO.getNomePrefeitura().toLowerCase() + "%");
			predicateList.add(predicateNomePrefeitura);
		}
		
		if (aprovacaoPrefeituraFiltroDTO.getStatus() != null && !aprovacaoPrefeituraFiltroDTO.getStatus().equals("")) {
			Path<String> status = aprovacaoPrefeitura.get("status");
			Predicate predicateStatus = cb.equal(status, aprovacaoPrefeituraFiltroDTO.getStatus());
			predicateList.add(predicateStatus);
		}
		
		if(aprovacaoPrefeituraFiltroDTO.getDataInicioMandato() != null && !aprovacaoPrefeituraFiltroDTO.getDataInicioMandato().equals("")) {
			Expression<LocalDate> campoDataHora = cb.function("date", LocalDate.class, joinPrefeitura.get("inicioMandato"));
			LocalDate dataInicioFormatada = LocalDate.parse(aprovacaoPrefeituraFiltroDTO.getDataInicioMandato(), df);
			predicateList.add(cb.greaterThanOrEqualTo(campoDataHora, dataInicioFormatada));
		}
		
		if(aprovacaoPrefeituraFiltroDTO.getDataFimMandato() != null && !aprovacaoPrefeituraFiltroDTO.getDataFimMandato().equals("")) {
			Expression<LocalDate> campoDataHora = cb.function("date", LocalDate.class, joinPrefeitura.get("fimMandato"));
			LocalDate dataFimFormatada = LocalDate.parse(aprovacaoPrefeituraFiltroDTO.getDataFimMandato(), df);
			predicateList.add(cb.lessThanOrEqualTo(campoDataHora, dataFimFormatada));
		}
		
		if(aprovacaoPrefeituraFiltroDTO.getDataPedidoCadastramento() != null && !aprovacaoPrefeituraFiltroDTO.getDataPedidoCadastramento().equals("")) {
			Expression<LocalDate> campoDataHora = cb.function("date", LocalDate.class, aprovacaoPrefeitura.get("data"));
			LocalDate dataPedidoCadastramentoFormatada = LocalDate.parse(aprovacaoPrefeituraFiltroDTO.getDataPedidoCadastramento(), df);
			predicateList.add(cb.equal(campoDataHora, dataPedidoCadastramentoFormatada));
		}

		javax.persistence.criteria.Predicate[] predicates = new javax.persistence.criteria.Predicate[predicateList.size()];
		predicateList.toArray(predicates);
		query.where(predicates);

		TypedQuery<AprovacaoPrefeituraDTO> typedQuery = em.createQuery(query);
		List<AprovacaoPrefeituraDTO> listaAprovacoesPrefeiturasDTO = typedQuery.getResultList();

		return listaAprovacoesPrefeiturasDTO;
	}
	
	 public AprovacaoPrefeitura buscarPorId(Long id) {
		Optional<AprovacaoPrefeitura> obj = repository.findById(id);
		return obj.orElseThrow(() -> new ObjectNotFoundException("Aprova????o de prefeitura n??o encontrada!"));
	}

	public List<AprovacaoPrefeitura> getAprovacoesPendentesByCidade(Cidade cidade) {
		return repository.findByPrefeituraCidadeAndStatus(cidade, "Pendente");
	}

	private AprovacaoPrefeitura alterarStatus(Long idAprovacaoPrefeitura, String status, String justificativa) {
		Optional<AprovacaoPrefeitura> aprovacao = repository.findById(idAprovacaoPrefeitura);

		if (aprovacao.isPresent()) {
			aprovacao.get().setStatus(status);
			aprovacao.get().setJustificativa(justificativa);
			aprovacao.get().setDataAprovacao(new Date());
			AprovacaoPrefeitura entity = repository.save(aprovacao.get());
			return entity;
		}

		return null;
	}

	public boolean emailAprovacaoPrefeitura(EmailToken emailToken) throws EmailException {
		try {
			String urlPCS = profileUtil.getProperty("profile.frontend");

			String aoA = "Ao";
			String prefeitoA = emailToken.getAprovacaoPrefeitura().getPrefeitura().getCargo();
			String nomeCidade = emailToken.getAprovacaoPrefeitura().getPrefeitura().getCidade().getNome();
			String uf = emailToken.getAprovacaoPrefeitura().getPrefeitura().getCidade().getProvinciaEstado().getNome();
			String excelentissimoA = "Excelent??ssimo";
			String senhorA = "Senhor";
			String parabenizaloA = "Parabeniz??-lo";
			if (emailToken.getAprovacaoPrefeitura().getPrefeitura().getCargo().equals("Prefeita")) {
				aoA = "??";
				prefeitoA = "Prefeita";
				excelentissimoA = "Excelent??ssima";
				senhorA = "Senhora";
				parabenizaloA = "Parabeniz??-la";
			}
			String nomePrefeito = emailToken.getAprovacaoPrefeitura().getPrefeitura().getNome();
			String urlSeloCidadeParticipante = "http://www.cidadessustentaveis.org.br/downloads/selos/selo-cidade-participante.pdf";
			String urlNovosIndicadores = "http://www.cidadessustentaveis.org.br/arquivos/260-Indicadores-do-Programa-Cidades Sustent%C3%A1veis.pdf";
			String urlPortal = urlPCS;
			String urlFormularioGestorPublico = urlPCS + "/add-responsavel?token=" + emailToken.getHash();
			String urlGuiaOrientadorParaConstrucaoObservatorios = "http://www.cidadessustentaveis.org.br/downloads/arquivos/guia-uso-sistema-indicadores.pdf";
			String urlGuiaOrientadorParaMapaDesigualdade = "http://www.cidadessustentaveis.org.br/noticias/mapa-da-desigualdade-orienta-construcao-de-politicas-publicas-na-cidade.pdf";
			String urlAcessoJusticaBrasil = "http://www.cidadessustentaveis.org.br/arquivos/acessoajusticanobrasil.pdf";
			String urlPlataformaODS = "http://agenda2030.com.br/contato.php";
			String urlCidadesSustentaveis = urlPCS;
			String urlEixosIndicadores = "https://www.cidadessustentaveis.org.br/institucional/pagina/eixos-do-pcs";
			String urlPremioPcs2019 = " https://www.cidadessustentaveis.org.br/premio-pcs-2019/";

			List<String> emails = new ArrayList<String>();
			String[] listaEmail = emailToken.getAprovacaoPrefeitura().getPrefeitura().getEmail().trim().split(";");
			for (String email : listaEmail) {
				emails.add(email.replace(";", " ").trim());
			}

			/*
			 * emails.add(emailToken.getAprovacaoPrefeitura().getPrefeitura().getEmail().
			 * trim());
			 */
			String mensagem = "<p style='font-family:Arial, Helvetica, sans-serif; font-size:16px; color:#000000;'>"
				+ aoA + "<br />" + "<br />" + prefeitoA + " de " + nomeCidade + "/" + uf + "<br /><br />"
				+ senhorA + " "	+ nomePrefeito + "<br />"
				+ "<p>" + "Queremos " + parabenizaloA +  " pela ades??o ao Programa Cidades Sustent??veis. Com essa iniciativa, sua administra????o poder?? "
				+ "contar com um conjunto de ferramentas para aprimorar os instrumentos de gest??o, tais como: um software para a inclus??o dos indicadores sociais, "
				+ "econ??micos, pol??ticos, ambientais e culturais de sua cidade, a Plataforma do Conhecimento; um banco de boas pr??ticas de pol??ticas p??blicas "
				+ "que alcan??aram resultados positivos em v??rias cidades do Brasil e do mundo; um conjunto de diretrizes para colaborar e inspirar suas propostas "
				+ "de pol??ticas p??blicas e seu Plano de Metas." + "</p>"
				+ "<p>" + "Os(as) prefeitos(as) que assinam a Carta Compromisso se comprometem tamb??m a elaborar o diagn??stico do munic??pio a partir dos "
				+ "indicadores do PCS, al??m do Plano de Metas para os quatros anos de gest??o." + "<p>"
				+ "<p>" + "Por meio desses compromissos, ressaltamos a import??ncia de se prestar contas das a????es desenvolvidas e dos avan??os alcan??ados "
				+ "por meio de relat??rio, revelando a evolu????o dos indicadores b??sicos relacionados a cada eixo. Este relat??rio dever?? ser publicado e "
				+ "divulgado no final do segundo ano de mandato e tamb??m apresentado em audi??ncia p??blica, de acordo com o item 3 da Carta Compromisso." + "</p>"
				+ "<p>" + "Os gestores que aderem ao Programa Cidades Sustent??veis t??m 120 dias ap??s a posse, ou a data da ades??o, para apresentarem o "
				+ "diagn??stico e o Plano de Metas. O ideal ?? que todos os indicadores b??sicos propostos sejam definidos. Entretanto, "
				+ "se alguns ainda n??o estiverem dispon??veis, ?? importante que os gestores informem o est??gio do levantamento que est?? sendo feito "
				+ "para a obten????o de cada um deles." + "</p>"
				+ "<p>" + "Em 2016, o Programa Cidades Sustent??veis ingressou em uma nova etapa, ao correlacionar seus eixos e indicadores "
				+ "aos 17 objetivos e 169 metas dos ODS (Objetivos de Desenvolvimento Sustent??vel), da Organiza????o das Na????es Unidas. "
				+ "Desse modo, o PCS cumpre um papel fundamental para a implementa????o da Agenda 2030 em n??vel local e para a municipaliza????o "
				+ "dos ODS nas cidades brasileiras." + "</p>"
				+ "<p>" + "Aprovada pela Assembleia Geral da ONU, a Agenda 2030 tem o prop??sito de acabar com a pobreza e promover, "
				+ "universalmente, a prosperidade econ??mica, o desenvolvimento social e a prote????o ambiental. Ao incorporar os objetivos e "
				+ "metas dos ODS em sua plataforma, principalmente aqueles em que as prefeituras t??m o protagonismo central no monitoramento e "
				+ "implementa????o, o PCS d?? uma importante contribui????o para o fortalecimento da gest??o p??blica em n??vel local e "
				+ "para a constru????o de cidades mais justas, democr??ticas e sustent??veis." + "</p>"
				+ "<p>" + "<strong>" + "Indicadores" + "</strong>" + "</p>"
				+ "<p>" + "O n??mero m??nimo de indicadores b??sicos varia de acordo com tr??s categorias populacionais: 50 para cidades pequenas "
				+ "(at?? 100 mil habitantes), 75 para cidades m??dias (de 101 mil a 500 mil habitantes) e 100 para cidades grandes e "
				+ "metr??poles (acima de 500 mil habitantes). A sele????o dos indicadores ser?? de responsabilidade da gest??o, a partir de um conjunto "
				+ "de 260 indicadores classificados como b??sicos pelo Programa Cidades Sustent??veis. Confira aqui os <a href=' " + urlEixosIndicadores + " '>eixos e indicadores do PCS." + "</a>" + "</p>"
				+ "<p>" + "<strong>" + "??rea exclusiva no novo portal do Programa Cidades Sustent??veis" + "</strong>" + "</p>"
				+ "<p>" + "Os signat??rios do Programa Cidades Sustent??veis tem ?? disposi????o um espa??o virtual (software) no portal "
				+ "<a href='" + "www.cidadessustentaveis.org.br" +"'>www.cidadessustentaveis.org.br</a> para apresentar o diagn??stico do munic??pio por meio dos indicadores, "
				+ "o Plano de Metas e divulgar boas pr??ticas. Este espa??o virtual cumpre uma dupla fun????o: ?? fonte de informa????o para o planejamento, "
				+ "gest??o e tomada de decis??o da administra????o p??blica, assim como de transpar??ncia, acompanhamento e fiscaliza????o por parte da sociedade." + "</p>"
				+ "<p>" + "Para ter acesso ao sistema, ?? necess??rio o preenchimento do <a href=' " + urlFormularioGestorPublico + " '>formul??rio. " + "</a>"
				+ "Ap??s o preenchimento, a senha de acesso ao sistema ser?? enviada automaticamente por e-mail." + "</p>"
				+ "<p>" + "<strong>" + "Programa de Forma????o e Capacita????o de profissionais nas ??reas de pol??ticas p??blicas" + "</strong>" + "</p>"
				+ "<p>" + "Para auxiliar os trabalhos nas diversas cidades que est??o seguindo os princ??pios do PCS, desde 2013, o programa vem promovendo "
				+ "pelo Pa??s cursos de capacita????o dirigidos aos gestores p??blicos e t??cnicos da administra????o municipal das cidades signat??rias. "
				+ "A capacita????o ?? realizada em dois momentos ??? um para Capacita????o Te??rica e outro para Capacita????o T??cnica. "
				+ "Ambos s??o estruturados em quatro m??dulos. Aos participantes s??o oferecidos materiais de apoio t??cnico: Guia GPS ??? "
				+ "Gest??o P??blica Sustent??vel, alinhados aos ODS, Guia Tem??tico de Indicadores e Guia de Refer??ncia para a produ????o de "
				+ "Indicadores e para Metas de Sustentabilidade para os munic??pios brasileiros." + "</p>"
				+ "<p>" + "A seguir, seguem as informa????es e as novas ferramentas lan??adas que est??o ?? disposi????o das prefeituras signat??rias:" + "</p>"
				+ "<p>" + "Guia de Usu??rio do Sistema ??? Elaborado para facilitar o uso da plataforma de dados do PCS, intencionalmente constru??da em um "
				+ "software aberto, de modo que permita a inclus??o de novos indicadores pelos seus usu??rios ??? gestores e t??cnicos que atuam nos munic??pios brasileiros." + "</p>"
				+ "<p>" + "Gest??o P??blica Sustent??vel (GPS) ??? Apresenta um conjunto de conceitos, ferramentas, metas, indicadores e pr??ticas exemplares para que a "
				+ "gest??o p??blica municipal possa avan??ar em planejamentos inovadores, com destaque para a implementa????o dos ODS em n??vel local." + "</p>"
				+ "<p>" + "Anexo GPS: atualizado com os ODS/Indicadores ??? Detalhamento dos 260 indicadores do PCS e sua correla????o com os ODS, incluindo as metas propostas pela ONU." + "</p>"
				+ "<p>" + "Guia orientador para a constru????o de Plano de metas ??? Informa????es pr??ticas para a constru????o dos Planos de Metas, sele????o dos indicadores, participa????o social, "
				+ "o sistema de monitoramento e a presta????o de contas." + "</p>"
				+ "<p>" + "Guia orientador para constru????o de Mapas da Desigualdade nos munic??pios brasileiros ??? Elaborado com o apoio da Funda????o Ford, "
				+ "o objetivo ?? orientar e incentivar os munic??pios brasileiros a reunirem os indicadores e concretizarem seus pr??prios mapas. "
				+ "Com essa ferramenta em m??os, as cidades ter??o a oportunidade de elaborar um diagn??stico preciso de suas regi??es administrativas e, com isso, "
				+ "implementar pol??ticas p??blicas que contribuam para a supera????o da desigualdade. " + "</p>"
				+ "<p>" + "Acesso ?? Justi??a no Brasil: ??ndice de Fragilidade dos Munic??pios ??? Resultado de uma parceria entre a Open Society Foundations, "
				+ "o Programa Cidades Sustent??veis e a Rede Nossa S??o Paulo, a publica????o sistematiza os dados existentes sobre o tema "
				+ "e prop??e um ??ndice para medir o n??vel de acesso ?? Justi??a em cada munic??pio do pa??s. O objetivo ?? contribuir para a reflex??o sobre as dificuldades "
				+ "para universalizar o acesso ?? Justi??a, bem como sobre o seu impacto na constru????o de uma sociedade mais igualit??ria, republicana e democr??tica. "
				+ "Al??m de tra??ar um panorama do acesso ?? justi??a no Brasil, o trabalho analisa tamb??m as iniciativas institucionais destinadas a tornar esse direito mais efetivo." + "</p>"
				+ "<p>" + "A????o Local pelo Clima ??? Auxilia os gestores p??blicos municipais na execu????o e/ou revis??o de a????es relacionadas ??s transforma????es "
				+ "do clima, para que possam preparar suas cidades para lidar melhor com os efeitos e impactos das mudan??as clim??ticas." + "</p>"
				+ "<p>" + "<strong>" + "Pr??mio Cidades Sustent??veis" + "</strong>"  +"</p>"
				+ "<p>" + "Reconhece pol??ticas p??blicas inovadoras e bem-sucedidas nas cidades brasileiras signat??rias que demonstram resultados concretos, "
				+ "baseados em indicadores de diversas ??reas da administra????o." + "</p>"
				+ "<p>" + "Tr??s edi????es j?? foram realizadas (2014, 2016 e 2019). Para mais informa????es sobre a terceira edi????o, realizada em setembro de 2019, "
				+ "acesse <a href=' " + urlPremioPcs2019 + " '>https://www.cidadessustentaveis.org.br/premio-pcs-2019/</a>" + "</p>"
				+ "<p>" + "A metodologia do PCS j?? foi adotada em sete cidades da Am??rica Latina: Pilar, Encarna????o, Cidade de Leste, S??o Lorenzo, Concep????o, Paraguari e Assun????o." + "</p>"
				+ "<p>" + "Benef??cios para as cidades participantes:" + "</p>"
				+ "<p>" + "??? Alinha o planejamento da cidade ?? mais avan??ada plataforma de desenvolvimento sustent??vel e ?? Agenda 2030, "
				+ "das Na????es Unidas, considerando como crit??rios b??sicos a promo????o da sustentabilidade, a inclus??o social e o respeito aos direitos humanos;" + "</p>"
				+ "<p>" + "??? Amplia o di??logo e a participa????o da sociedade para a constru????o conjunta de pol??ticas p??blicas e de mecanismos de transpar??ncia e controle social;" + "</p>"
				+ "<p>" + "??? Possibilita o bom planejamento e execu????o or??ament??ria, proporcionando maior capacidade de previsibilidade, "
				+ "supress??o de desperd??cios e ganhos de produtividade. Isso permitir?? ampliar a capacidade de realiza????o da gest??o e traz benef??cios e "
				+ "economias importantes para a m??quina p??blica;" + "</p>"
				+ "<p>" + "??? Amplia as possibilidades de capta????o de novos recursos p??blicos, privados ou de organismos internacionais, em fun????o de uma gest??o planejada e do compromisso com os ODS;" + "</p>"
				+ "<p>" + "Em 2018, com o apoio do Fundo Global para o Meio Ambiente, o PCS iniciou a amplia????o da Plataforma Cidades Sustent??veis, "
				+ "para oferecer mais funcionalidades e ferramentas para a constru????o de pol??ticas p??blicas voltadas ao desenvolvimento sustent??vel "
				+ "e ?? implementa????o dos ODS no Brasil." + "</p>"
				+ "<p>" + "<strong>" + "Sobre a Plataforma do Conhecimento:" + "</strong>" + "</p>"
				+ "<p>" + "&nbsp;&nbsp;&nbsp;&nbsp;O Citinova ?? um projeto multilateral, realizado pelo Minist??rio da Ci??ncia, Tecnologia, Inova????es e Comunica????es (MCTIC), com apoio do Fundo Global para o Meio Ambiente "
				+ "(GEF, na sigla em ingl??s), gest??o da ONU Meio Ambiente, e participa????o dos parceiros coexecutores Ag??ncia Recife para Inova????o e Estrat??gia (ARIES) e Porto Digital, "
				+ "Centro de Gest??o de Estudos Estrat??gicos (CGEE), Secretaria do Meio Ambiente (SEMA/GDF) e Programa Cidades Sustent??veis (PCS).\r\n "
				+ "&nbsp;&nbsp;&nbsp;&nbsp;O projeto est?? sendo desenvolvido no ??mbito do programa GEF-6 e tem como um dos objetivos desenvolver um ambiente web chamado Plataforma Cidades Sustent??veis, "
				+ "no qual ser??o disponibilizadas tecnologias, ferramentas e metodologias em planejamento urbano integrado para gestores p??blicos municipais, "
				+ "conte??dos t??cnicos e te??ricos, al??m de not??cias e informa????es sobre sustentabilidade urbana para o p??blico geral.\r\n" + "</p>"
				+ "<p>" + "&nbsp;&nbsp;&nbsp;&nbsp;A primeira vers??o da plataforma foi lan??ada em setembro de 2019 e incorporar??, ao longo dos pr??ximos anos, novos conte??dos, "
				+ "ferramentas, metodologias e funcionalidades para os usu??rios. Dentre os recursos oferecidos, as prefeituras contam com sistemas para o monitoramento e "
				+ "an??lise de dados e indicadores, constru????o de metas e planejamento integrado de a????es em diferentes ??reas da administra????o municipal ??? como transportes, "
				+ "habita????o, assist??ncia social, sa??de e educa????o, entre outras." + "</p>"
				+ "<p>" + "&nbsp;&nbsp;&nbsp;&nbsp;A nova plataforma ?? integrada ao Observat??rio da Inova????o, um conjunto de tecnologias desenvolvidas para diferentes tipologias de cidades, "
				+ "a fim de apoiar gestores municipais na produ????o de diagn??sticos e identifica????o de solu????es em planejamento urbano. O observat??rio est?? sendo desenvolvido pelo CGEE, "
				+ "organiza????o social que produz estudos e pesquisas prospectivas, avalia????es de estrat??gias em pol??ticas p??blicas e outras atividades nas ??reas de educa????o, "
				+ "ci??ncia, tecnologia e inova????o." + "</p>"
				+ "<p>" + "M??dulos atuais da nova Plataforma Cidades Sustent??veis" + "</p>"
				+ "<ol>" + "<li>" + "Indicadores/Metas" + "</li>"
				+ "<p>" + "&nbsp;&nbsp;&nbsp;&nbsp;Sistema desenvolvido para auxiliar gestores p??blicos no planejamento urbano municipal, por meio de um conjunto de "
				+ "indicadores para monitoramento do desempenho de pol??ticas p??blicas e apoio ao diagn??stico municipal." + "</p>"
				+ "<p>" + "&nbsp;&nbsp;&nbsp;&nbsp;Permite a elabora????o de an??lises, a gera????o de relat??rios e o estabelecimento de metas, bem como o monitoramento de dados e "
				+ "informa????es por parte da sociedade civil e da prefeitura." + "</p>"
				+ "<li>" + "Boas pr??ticas" + "</li>"
				+ "<p>" + "&nbsp;&nbsp;&nbsp;&nbsp;Banco de boas pr??ticas em pol??ticas p??blicas do Brasil e do mundo, produzido pela equipe do Programa Cidades Sustent??veis "
				+ "com o objetivo de divulgar casos exemplares de a????es que geraram impacto positivo no espa??o urbano." + "</p>"
				+ "<p>" + "&nbsp;&nbsp;&nbsp;&nbsp;Contar?? tamb??m com se????o espec??fica para a publica????o de boas pr??ticas produzidas pelas prefeituras signat??rias do PCS, "
				+ "al??m de links para solu????es sustent??veis e inovadoras contextualizadas no territ??rio nacional por meio de tipologias de cidades-regi??o, no Observat??rio de Inova????o." + "</p>"
				+ "<p>" + "M??dulos que ser??o incorporados nos pr??ximos anos" + "</p>"
				+ "<li>" + "Planejamento integrado " + "</li>"
				+ "<p>" + "&nbsp;&nbsp;&nbsp;&nbsp;Disponibiliza informa????es e ferramentas para o entendimento e a implanta????o de um sistema de planejamento integrado em n??vel municipal. "
				+ "Al??m de metodologia e conte??dos t??cnicos e conceituais sobre o tema (guias, manuais, pesquisas e aplica????es), oferecer?? ferramentas matem??ticas "
				+ "(equa????es, fun????es, modelos), estat??sticas e de geoprocessamento (SIG) que permitam a integra????o de dados e "
				+ "informa????es para o desenvolvimento do planejamento integrado. " + "</p>"
				+ "<li>" + "Participa????o cidad??" + "</li>"
				+ "<p>" + "&nbsp;&nbsp;&nbsp;&nbsp;Tem o objetivo de sensibilizar e capacitar a sociedade civil e os gestores municipais para que a participa????o cidad?? seja incorporada como "
				+ "m??todo de gest??o municipal, por meio do estabelecimento e fortalecimento de sistemas municipais de participa????o da sociedade civil "
				+ "(conselhos, audi??ncias p??blicas, acesso ?? informa????o, etc.)." + "</p>"
				+ "<p>" + "&nbsp;&nbsp;&nbsp;&nbsp;O m??dulo oferece instrumentos conceituais e administrativos para o processo de capacita????o dos gestores e da sociedade civil, "
				+ "al??m de ferramentas interativas para os usu??rios (f??rum, consultas p??blicas, testemunhos, etc.)." + "</p>"
				+ "<li>" + "Financiamento municipal" + "</li>"
				+ "<p>" + "&nbsp;&nbsp;&nbsp;&nbsp;Banco de dados para consulta com indica????o de fontes de financiamento para a esfera p??blica municipal e orienta????es sobre gest??o or??ament??ria. "
				+ "Serve como suporte ?? busca de recursos e ?? capacita????o para a gest??o or??ament??ria local." + "</p>"
				+ "<p>" + "&nbsp;&nbsp;&nbsp;&nbsp;O m??dulo tamb??m permite a identifica????o de fontes de financiamento (nacionais e internacionais, p??blicas e privadas) "
				+ "para o desenvolvimento e implanta????o de planos e programas municipais. Traz ainda orienta????es sobre coopera????o t??cnica e "
				+ "modelos de projetos que atendem ??s exig??ncias dos programas de financiamento p??blico." + "</p>"
				+ "<li>" + "Treinamento e capacita????o" + "</li>"
				+ "<p>" + "&nbsp;&nbsp;&nbsp;&nbsp;Oferece acesso a todos os materiais utilizados nas diversas atividades de capacita????o do Programa Cidades Sustent??veis, "
				+ "com acesso livre para usu??rios em geral e, para os gestores municipais, acesso por meio de login ?? se????o exclusiva em que poder??o "
				+ "acompanhar o desenvolvimento das atividades de capacita????o. " + "</p>"
				+ "<li>" + "Colabora????es acad??micas " + "</li>"
				+ "<p>" + "&nbsp;&nbsp;&nbsp;&nbsp;Traz orienta????es aos gestores p??blicos e materiais de mobiliza????o da comunidade acad??mica para a elabora????o de conv??nios e "
				+ "parcerias entre prefeituras e institui????es de pesquisa para o desenvolvimento, divulga????o e aplica????o de novas tecnologias e "
				+ "metodologias para a gest??o local. Prev?? a????es de transfer??ncia, desenvolvimento e aplica????o de novas tecnologias voltadas para a gest??o p??blica municipal." + "</p>"
				+ "<li>" + "Colabora????es privadas" + "</li>"
				+ "<p>" + "&nbsp;&nbsp;&nbsp;&nbsp;Ambiente para estimular o desenvolvimento de parcerias e a troca de experi??ncias entre o poder p??blico municipal e o "
				+ "setor privado, com foco no entendimento de PPPs, Arranjos Produtivos Locais (APLs), cadeias produtivas e instrumentos de "
				+ "gest??o que direcionem para a moderniza????o e efici??ncia administrativa. Tamb??m aborda o papel da responsabilidade empresarial na "
				+ "constru????o de cidades mais justas e sustent??veis, e abre espa??o para o monitoramento e controle social de parcerias entre o poder p??blico e o setor privado. " + "</p>"
				+ "<li>" + "Leis, planos e afins" + "</li>"
				+ "<p>" + "&nbsp;&nbsp;&nbsp;&nbsp;Oferece suporte e orienta????o ?? leitura integrada de leis e planos estaduais e federais determinantes para as atividades de "
				+ "planejamento municipal no ??mbito da Plataforma do Programa Cidades Sustent??veis." + "</p>"
				+ "<li>" + "Agenda de eventos" + "</li>"
				+ "<p>" + "&nbsp;&nbsp;&nbsp;&nbsp;Oferece manuais e guias para orienta????o ?? produ????o de eventos no ??mbito do Programa Cidades Sustent??veis e apresenta "
				+ "diversas ferramentas para cria????o, organiza????o, divulga????o e desenvolvimento de eventos." + "</p>" + "</ol>"
				+ "<p>" + "Para eventuais esclarecimentos, por favor, entre em contato pelos telefones (11) 3894.2400 ou 99457.6085, ou ainda pelo e-mail zuleica@isps.org.br" + "</p>"
				+ "<p>" + "Esperamos contribuir com o ??xito de sua gest??o e com a melhoria da qualidade de vida nas cidades brasileiras." + "</p>"
				+ "<p>" + "Abra??os, " + "</p>"
				+ "<p>" + "Zuleica Goulart" + "</p>"
				+ "<p>" + "Coordenadora de Mobiliza????o do Programa Cidades Sustent??veis" + "</p>"
				+ "<p>" + "<a href='" + "www.cidadessustentaveis.org.br" +"'>www.cidadessustentaveis.org.br</a>" + "</p>";

			emailUtil.enviarEmailHTML(emails, "Aprova????o no PCS!", mensagem);

		} catch (Exception ex) {
			return false;
		}

		return true;
	}

	public boolean emailAprovacaoPrefeituraV2(EmailToken emailToken) throws EmailException {
		try {
			String urlPCS = profileUtil.getProperty("profile.frontend");

			String aoA = "Ao";
			String ao = "o";
			String prefeitoA = emailToken.getAprovacaoPrefeitura().getPrefeitura().getCargo();
			String nomeCidade = emailToken.getAprovacaoPrefeitura().getPrefeitura().getCidade().getNome();
			String uf = emailToken.getAprovacaoPrefeitura().getPrefeitura().getCidade().getProvinciaEstado().getNome();
			String excelentissimoA = "Excelent??ssimo";
			String prezadoA = "Prezado";
			String parabenizaloA = "Parabeniz??-lo";
			String senhorA = "senhor";
			if (emailToken.getAprovacaoPrefeitura().getPrefeitura().getCargo().equals("Prefeita")) {
				aoA = "??";
				ao = "a";
				prefeitoA = "Prefeita";
				excelentissimoA = "Excelent??ssima";
				prezadoA = "Prezada";
				parabenizaloA = "Parabeniz??-la";
				senhorA = "senhora";
			}
			String nomePrefeito = emailToken.getAprovacaoPrefeitura().getPrefeitura().getNome();
			String urlSeloCidadeParticipante = "http://www.cidadessustentaveis.org.br/downloads/selos/selo-cidade-participante.pdf";
			String urlNovosIndicadores = "http://www.cidadessustentaveis.org.br/arquivos/260-Indicadores-do-Programa-Cidades Sustent%C3%A1veis.pdf";
			String urlPortal = urlPCS;
			String urlFormularioGestorPublico = urlPCS + "/add-responsavel?token=" + emailToken.getHash();
			String urlGuiaOrientadorParaConstrucaoObservatorios = "http://www.cidadessustentaveis.org.br/downloads/arquivos/guia-uso-sistema-indicadores.pdf";
			String urlGuiaOrientadorParaMapaDesigualdade = "http://www.cidadessustentaveis.org.br/noticias/mapa-da-desigualdade-orienta-construcao-de-politicas-publicas-na-cidade.pdf";
			String urlAcessoJusticaBrasil = "http://www.cidadessustentaveis.org.br/arquivos/acessoajusticanobrasil.pdf";
			String urlPlataformaODS = "http://agenda2030.com.br/contato.php";
			String urlCidadesSustentaveis = urlPCS;
			String urlEixosIndicadores = "https://www.cidadessustentaveis.org.br/institucional/pagina/eixos-do-pcs";
			String urlPremioPcs2019 = " https://www.cidadessustentaveis.org.br/premio-pcs-2019/";
			String urlGuiaUsoSistema = "https://www.cidadessustentaveis.org.br/arquivos/Publicacoes/Guia_de_Uso_do_Sistema.pdf";
			List<String> emails = new ArrayList<String>();
			String[] listaEmail = emailToken.getAprovacaoPrefeitura().getPrefeitura().getEmail().trim().split(";");
			for (String email : listaEmail) {
				emails.add(email.replace(";", " ").trim());
			}

			/*
			 * emails.add(emailToken.getAprovacaoPrefeitura().getPrefeitura().getEmail().
			 * trim());
			 */
			String mensagem = "<p><a href='"+ urlCidadesSustentaveis +"'><img src='https://www.cidadessustentaveis.org.br/assets/logos/f-logo__programa-cidades-sustentaveis.jpg' style='height: 80px;margin-left: 2%;'></a></p>"
				+ "<p>" + aoA + "<br />" + "<br />" + prefeitoA + " de " + nomeCidade + ", " + uf + "<br /><br />"
				+ prezadoA + " "	+ nomePrefeito + ",<br /></p>"
				+ "<p>Seja bem-vind"+ao+" ao Programa Cidades Sustent??veis. ?? com grande entusiasmo que recebemos sua ades??o, por meio da assinatura da carta-compromisso. Estamos prontos para seguir trabalhando na constru????o de cidades mais justas, democr??ticas e sustent??veis. </p>"
				+ "<p>Ao assinar a carta compromisso do PCS, "+ao+" prefeit"+ao+" se compromete em adotar uma agenda de desenvolvimento urbano com foco na constru????o de pol??ticas p??blicas estruturantes, alinhadas aos objetivos e metas da Agenda 2030, da Organiza????o das Na????es Unidas (ONU). Para isso, conta com o apoio dos conte??dos, ferramentas, metodologias e capacita????es desenvolvidos pelo programa e disponibilizados gratuitamente em nosso ambiente web, a <a href='" +urlPCS+ "'>Plataforma Cidades Sustent??veis</a>.  <p>"
				+ "<p><strong>Primeiro Acesso</strong><br />Antes de iniciar os trabalhos, leia as orienta????es iniciais para o cadastro de usu??rios da prefeitura na Plataforma.</p>"
				+ "<p>O primeiro passo para ter acesso ao sistema ?? definir os respons??veis da prefeitura para algumas atribui????es pr??-definidas, relacionadas ao uso das ferramentas online e alimenta????o das bases de dados, de indicadores e demais informa????es da cidade. Para isso, basta preencher e enviar este <a href='" +urlFormularioGestorPublico+ "'>formul??rio</a>. Em seguida, uma senha de acesso ao sistema ser?? enviada automaticamente aos e-mails fornecidos por meio deste formul??rio.</p>"
				+ "<p>Ap??s a cria????o dos usu??rios o " + senhorA + " tamb??m receber?? um e-mail para a cria????o de sua senha. Neste caso, ser?? utilizado o e-mail indicado na carta-compromisso, assinada no momento de ades??o ao PCS. </p>"
				+ "<p>Uma vez feito o login no sistema, os usu??rios ter??o acesso a ferramentas, se????es e m??dulos desenvolvidos exclusivamente para gestores p??blicos e t??cnicos das prefeituras.</p>"
				+ "<p>Na plataforma do PCS, as cidades podem criar suas pr??prias p??ginas, disponibilizar dados e informa????es municipais (como indicadores, Plano de Metas e boas pr??ticas locais), subir e baixar camadas do Sistema de Informa????es Geogr??ficas (SIG) ??? uma nova ferramenta criada por nossa equipe ???, al??m de continuar navegando pelas se????es e conte??dos abertos ao p??blico geral.</p>"
				+ "<p>Para se familiarizar com a plataforma, navegue pelas diferentes op????es do Menu e leia o <a href=' "+urlGuiaUsoSistema+ "'>Guia de Uso do Sistema</a>. Em caso de d??vidas, escreva para contato@cidadessustentaveis.org.br</p>"
				+ "<p>Desejamos muito sucesso em sua jornada ao longo dos pr??ximos quatro anos e esperamos contribuir para a melhoria da qualidade de vida da popula????o de "+nomeCidade+" nesse per??odo. Temos certeza de que o PCS pode dar um grande apoio nesse sentido. Conte com a gente.</p>"
				+ "<p>" + "Atenciosamente, " + "</p>"
				+ "<p>" + "Equipe do Programa Cidades Sustent??veis" + "</p>"
				+ "<p>" + "<a href='" + "www.cidadessustentaveis.org.br" +"'>www.cidadessustentaveis.org.br</a>" + "</p>";

			emailUtil.enviarEmailHTML(emails, "Aprova????o no PCS!", mensagem);

		} catch (Exception ex) {
			return false;
		}

		return true;
	}

	public boolean emailReprovacaoPrefeitura(AprovacaoPrefeitura aprovacaoPrefeitura, String justificativa)
			throws EmailException {
		try {
			String urlPCS = profileUtil.getProperty("profile.frontend");

			String aoA = "Ao";
			String prefeitoA = aprovacaoPrefeitura.getPrefeitura().getCargo();
			String nomeCidade = aprovacaoPrefeitura.getPrefeitura().getCidade().getNome();
			String uf = aprovacaoPrefeitura.getPrefeitura().getCidade().getProvinciaEstado().getNome();
			String excelentissimoA = "Excelent??ssimo";
			String senhorA = "Senhor";
			String parabenizaloA = "Parabeniz??-lo";
			if (aprovacaoPrefeitura.getPrefeitura().getCargo().equals("Prefeita")) {
				aoA = "??";
				prefeitoA = "Prefeita";
				excelentissimoA = "Excelent??ssima";
				senhorA = "Senhora";
				parabenizaloA = "Parabeniz??-la";
			}
			String nomePrefeito = aprovacaoPrefeitura.getPrefeitura().getNome();
			String urlCidadesSustentaveis = urlPCS;

			List<String> emails = new ArrayList<String>();
			emails.add(aprovacaoPrefeitura.getPrefeitura().getEmail().trim());

			String mensagem = "<p style='font-family:Arial, Helvetica, sans-serif; font-size:16px; color:#000000;'>"
					+ aoA + " " + prefeitoA + " de " + nomeCidade + "/" + uf + "<br><br>" 
					+ excelentissimoA + " " + "<b>" + senhorA + "</b>" + " " + nomePrefeito + ","
					+ "Queremos lhe informar que a sua solicita????o de ades??o ao Programa Cidades Sustent??veis foi <b style='color:#ff0000;'>negada</b> pelo motivo abaixo:"
					+ "<br><br>" + "<b>Motivo da reprova????o: </b>" + justificativa + "<br><br>"
					+ "Para eventuais esclarecimentos, por favor, entre em contato pelos telefones (11) 3894.2400 ou 99457.6085."
					+ "<br><br>" + "Esperamos contribuir com o ??xito de sua gest??o e com a melhoria da qualidade de vida nas cidades brasileiras no futuro."
					+ "<br />Abra??os, " + "<br />Zuleica Goulart "
					+ "<br />Coordenadora do Programa Cidades Sustent??veis " + "<br /><a href='"
					+ urlCidadesSustentaveis + "'>" + urlCidadesSustentaveis + "</a>" + "</p>";

			emailUtil.enviarEmailHTML(emails, "Reprova????o no PCS!", mensagem);
		} catch (Exception ex) {
			return false;
		}

		return true;
	}
}
